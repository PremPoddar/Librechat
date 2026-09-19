package com.example.librechat

import com.example.librechat.db.MessageDao
import com.example.librechat.db.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One line in a chat. [mine] is true for messages this phone sent, so they can be shown differently. */
data class ChatMessage(
    val fromId: String,
    val fromName: String,
    val text: String,
    val mine: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Another phone we know about.
 *
 * [nearby] is true when its packets reach us directly. A peer that is not nearby is further away
 * and its packets are being passed on by other phones.
 *
 * [lastSeen] is when we last heard anything from it, which is how phones that have left are
 * removed from the list.
 */
data class Peer(
    val id: String,
    val name: String,
    val nearby: Boolean,
    val lastSeen: Long,
)

enum class ChatRequestStatus {
    NONE, PENDING_SENT, PENDING_RECEIVED, ACCEPTED
}

/**
 * Holds everything the screens display. Nothing is written to disk, so chats start empty every
 * time the app is opened.
 *
 * The values are StateFlows because Compose can watch them and redraw a screen by itself whenever
 * a message or a device arrives.
 */
class ChatStore(
    private val settings: Settings? = null,
    private val messageDao: MessageDao? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val peerList = MutableStateFlow<List<Peer>>(emptyList())
    
    // The full list of peers we have accepted, even if they are currently offline.
    private val persistentPeers = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _pairedPeers = MutableStateFlow<List<Peer>>(emptyList())
    val pairedPeers: StateFlow<List<Peer>> = _pairedPeers

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers

    val peers: StateFlow<List<Peer>> = peerList

    private val unreadIds = MutableStateFlow<Set<String>>(emptySet())
    val unreadChatIds: StateFlow<Set<String>> = unreadIds

    private val statuses = MutableStateFlow<Map<String, ChatRequestStatus>>(emptyMap())
    val chatStatuses: StateFlow<Map<String, ChatRequestStatus>> = statuses

    init {
        // Load paired peers from settings
        settings?.let { s ->
            val saved = s.pairedPeers.mapNotNull { 
                val parts = it.split("|", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            persistentPeers.value = saved
            
            // Pre-fill statuses with ACCEPTED for all persistent peers
            statuses.value = saved.mapValues { ChatRequestStatus.ACCEPTED }
        }

        // Load chat history from database
        messageDao?.let { dao ->
            scope.launch {
                val all = dao.getAllMessages()
                synchronized(this@ChatStore) {
                    all.forEach { entity ->
                        val chat = conversation(entity.chatId)
                        chat.value = chat.value + ChatMessage(
                            fromId = entity.fromId,
                            fromName = entity.fromName,
                            text = entity.text,
                            mine = entity.isMine,
                            timestamp = entity.timestamp
                        )
                    }
                }
            }
        }
    }

    // One conversation per chat: PUBLIC for the public chat, otherwise the other phone's id.
    private val conversations = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

    fun messages(chatId: String): StateFlow<List<ChatMessage>> = conversation(chatId)

    @Synchronized
    private fun conversation(chatId: String): MutableStateFlow<List<ChatMessage>> {
        return conversations.getOrPut(chatId) { MutableStateFlow(emptyList()) }
    }

    /** Called every time we hear from a phone, which both adds it and keeps it in the list. */
    @Synchronized
    fun addPeer(
        id: String,
        name: String,
        nearby: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        val updated = peerList.value.filter { it.id != id } + Peer(id, name, nearby, at)
        peerList.value = updated.sortedWith(compareByDescending<Peer> { it.nearby }.thenBy { it.name })
        
        // If this is a paired peer, update their name in persistence if it changed
        if (persistentPeers.value.containsKey(id) && persistentPeers.value[id] != name) {
            persistentPeers.value = persistentPeers.value + (id to name)
            settings?.addPairedPeer(id, name)
        }
        
        updateSplitFlows()
    }

    /** The direct link to this phone is gone, but we may still reach it through the mesh. */
    @Synchronized
    fun clearNearby(id: String) {
        peerList.value = peerList.value.map { if (it.id == id) it.copy(nearby = false) else it }
        updateSplitFlows()
    }

    /**
     * Forgets every phone we have not heard from since [before].
     *
     * This is how a phone that walked away or closed the app disappears from the list. There is no
     * message saying goodbye, and for a phone several hops away there is not even a Bluetooth link
     * to lose, so the only sign that it has gone is that its announcements stop arriving.
     */
    @Synchronized
    fun removeGone(before: Long) {
        peerList.value = peerList.value.filter { it.lastSeen >= before }
        updateSplitFlows()
    }

    private fun updateSplitFlows() {
        val online = peerList.value
        val paired = persistentPeers.value

        // Paired list: Everyone in 'paired', with online status if available
        val pairedList = paired.map { (id, name) ->
            online.find { it.id == id } ?: Peer(id, name, nearby = false, lastSeen = 0)
        }.sortedWith(compareByDescending<Peer> { it.lastSeen > 0 }.thenBy { it.name })

        // Discovered list: Everyone 'online' who is NOT in 'paired'
        val discoveredList = online.filter { !paired.containsKey(it.id) }

        _pairedPeers.value = pairedList
        _discoveredPeers.value = discoveredList
    }

    fun addIncoming(packet: Packet) {
        val chatId = if (packet.to == PUBLIC) PUBLIC else packet.from
        if (packet.type == TYPE_REQUEST) {
            updateStatus(chatId, ChatRequestStatus.PENDING_RECEIVED)
        } else if (packet.type == TYPE_ACCEPT) {
            updateStatus(chatId, ChatRequestStatus.ACCEPTED)
            return // Accept packet doesn't have text to show
        }

        val message = ChatMessage(packet.from, packet.name, packet.text, mine = false, timestamp = packet.timestamp)
        add(chatId, message)
        saveToDb(chatId, message)

        synchronized(unreadIds) {
            unreadIds.value = unreadIds.value + chatId
        }
    }

    fun addOutgoing(chatId: String, packet: Packet) {
        if (packet.type == TYPE_REQUEST) {
            updateStatus(chatId, ChatRequestStatus.PENDING_SENT)
        }
        val message = ChatMessage(packet.from, packet.name, packet.text, mine = true, timestamp = packet.timestamp)
        add(chatId, message)
        saveToDb(chatId, message)
    }

    private fun saveToDb(chatId: String, message: ChatMessage) {
        messageDao?.let { dao ->
            scope.launch {
                dao.insert(
                    MessageEntity(
                        chatId = chatId,
                        fromId = message.fromId,
                        fromName = message.fromName,
                        text = message.text,
                        isMine = message.mine,
                        timestamp = message.timestamp
                    )
                )
            }
        }
    }

    @Synchronized
    fun updateStatus(chatId: String, status: ChatRequestStatus) {
        if (chatId == PUBLIC) return
        statuses.value = statuses.value + (chatId to status)
        
        if (status == ChatRequestStatus.ACCEPTED) {
            val name = nameOf(chatId)
            if (!persistentPeers.value.containsKey(chatId)) {
                persistentPeers.value = persistentPeers.value + (chatId to name)
                settings?.addPairedPeer(chatId, name)
                updateSplitFlows()
            }
        }
    }

    fun statusOf(chatId: String): ChatRequestStatus {
        if (chatId == PUBLIC) return ChatRequestStatus.ACCEPTED
        return statuses.value[chatId] ?: ChatRequestStatus.NONE
    }

    @Synchronized
    fun markRead(chatId: String) {
        unreadIds.value = unreadIds.value - chatId
    }
    @Synchronized
    fun clearChat(chatId: String) {
        conversation(chatId).value = emptyList()
        unreadIds.value = unreadIds.value - chatId
        messageDao?.let { dao ->
            scope.launch {
                dao.deleteByChatId(chatId)
            }
        }
    }
    @Synchronized
    private fun add(chatId: String, message: ChatMessage) {
        val flow = conversation(chatId)
        flow.value = flow.value + message
    }

    fun nameOf(id: String): String = peerList.value.find { it.id == id }?.name ?: id
}

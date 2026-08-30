package com.example.librechat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One line in a chat. [mine] is true for messages this phone sent, so they can be shown differently. */
data class ChatMessage(
    val fromId: String,
    val fromName: String,
    val text: String,
    val mine: Boolean,
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
class ChatStore {

    private val peerList = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = peerList

    private val unreadIds = MutableStateFlow<Set<String>>(emptySet())
    val unreadChatIds: StateFlow<Set<String>> = unreadIds

    private val statuses = MutableStateFlow<Map<String, ChatRequestStatus>>(emptyMap())
    val chatStatuses: StateFlow<Map<String, ChatRequestStatus>> = statuses

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
    }

    /** The direct link to this phone is gone, but we may still reach it through the mesh. */
    @Synchronized
    fun clearNearby(id: String) {
        peerList.value = peerList.value.map { if (it.id == id) it.copy(nearby = false) else it }
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
    }

    fun addIncoming(packet: Packet) {
        val chatId = if (packet.to == PUBLIC) PUBLIC else packet.from
        if (packet.type == TYPE_REQUEST) {
            updateStatus(chatId, ChatRequestStatus.PENDING_RECEIVED)
        } else if (packet.type == TYPE_ACCEPT) {
            updateStatus(chatId, ChatRequestStatus.ACCEPTED)
            return // Accept packet doesn't have text to show
        }

        add(chatId, ChatMessage(packet.from, packet.name, packet.text, mine = false))
        synchronized(unreadIds) {
            unreadIds.value = unreadIds.value + chatId
        }
    }

    fun addOutgoing(chatId: String, packet: Packet) {
        if (packet.type == TYPE_REQUEST) {
            updateStatus(chatId, ChatRequestStatus.PENDING_SENT)
        }
        add(chatId, ChatMessage(packet.from, packet.name, packet.text, mine = true))
    }

    @Synchronized
    fun updateStatus(chatId: String, status: ChatRequestStatus) {
        if (chatId == PUBLIC) return
        statuses.value = statuses.value + (chatId to status)
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
    private fun add(chatId: String, message: ChatMessage) {
        val flow = conversation(chatId)
        flow.value = flow.value + message
    }

    fun nameOf(id: String): String = peerList.value.find { it.id == id }?.name ?: id
}

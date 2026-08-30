package com.example.librechat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How often this phone tells the mesh it is still here. */
private const val ANNOUNCE_EVERY_MS = 10_000L

/** A phone we have not heard from for this long is taken off the device list. */
private const val GONE_AFTER_MS = 30_000L

/**
 * Puts the whole mesh together.
 *
 * The two Bluetooth halves ([BleServer] and [BleClient]) both report the packets they receive to
 * [onLine]. That method asks [MeshRouter] what should happen, then does it: showing the message,
 * passing it on to the other phones, or both.
 */
class MeshManager(
    context: Context,
    val myName: String,
    /** The short id of this phone, used to address private messages. Comes from [Settings]. */
    val myId: String,
) {

    val store = ChatStore()

    private val router = MeshRouter(myId)
    private val server = BleServer(context, ::onLine, ::onLinkUp, ::onLinkDown)
    private val client = BleClient(context, ::onLine, ::onLinkUp, ::onLinkDown)

    // Bluetooth works with hardware addresses, the app works with node ids, so we remember which
    // is which in order to update the device list when a phone goes out of range.
    private val idByAddress = mutableMapOf<String, String>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        server.start()
        client.start()

        // Say hello over and over, and drop the phones that have stopped saying it back.
        scope.launch {
            while (isActive) {
                announce()
                store.removeGone(before = System.currentTimeMillis() - GONE_AFTER_MS)
                delay(ANNOUNCE_EVERY_MS)
            }
        }
    }

    fun stop() {
        scope.cancel()
        server.stop()
        client.stop()
    }

    /**
     * Tries to find nearby phones again.
     *
     * This is useful if Bluetooth was turned off and on again, as it cleans up the list and
     * asks the mesh for fresh announcements.
     */
    fun refresh() {
        server.stop()
        client.stop()
        server.start()
        client.start()
        store.removeGone(before = System.currentTimeMillis())
        announce()
    }

    /** Sends a message the user typed. Pass PUBLIC as [to] for the public chat. */
    fun send(to: String, text: String) {
        val status = store.statusOf(to)
        val packet = if (to != PUBLIC && status == ChatRequestStatus.NONE) {
            Packet.request(from = myId, name = myName, to = to, text = text)
        } else {
            Packet.message(from = myId, name = myName, to = to, text = text)
        }
        
        // Remember our own message so the copy that comes back through the mesh is ignored.
        router.remember(packet.id)
        store.addOutgoing(to, packet)
        sendToEveryone(packet, except = null)
    }

    /** Accepts a chat request from [chatId]. */
    fun accept(chatId: String) {
        store.updateStatus(chatId, ChatRequestStatus.ACCEPTED)
        val packet = Packet.accept(from = myId, name = myName, to = chatId)
        router.remember(packet.id)
        sendToEveryone(packet, except = null)
    }

    /** Tells everybody in the mesh that this phone is still here. */
    private fun announce() {
        val hello = Packet.hello(from = myId, name = myName)
        router.remember(hello.id)
        sendToEveryone(hello, except = null)
    }

    /** A packet arrived over one of the Bluetooth links. */
    private fun onLine(address: String, text: String) {
        val packet = Packet.fromJson(text) ?: return
        if (packet.from == myId) return

        when (val action = router.handle(packet)) {
            is Action.Drop -> Unit
            is Action.Deliver -> deliver(packet, address)
            is Action.Relay -> sendToEveryone(action.packet, except = address)
            is Action.DeliverAndRelay -> {
                deliver(packet, address)
                sendToEveryone(action.packet, except = address)
            }
        }
    }

    private fun deliver(packet: Packet, address: String) {
        // A packet that still has its full ttl has not been passed on by anybody yet, so it came
        // straight from the phone that wrote it and that phone is a direct neighbour.
        val nearby = packet.ttl == START_TTL
        if (nearby) idByAddress[address] = packet.from

        // Hearing anything from a phone is what keeps it in the device list.
        store.addPeer(packet.from, packet.name, nearby)

        if (packet.type == TYPE_MSG || packet.type == TYPE_REQUEST || packet.type == TYPE_ACCEPT) {
            store.addIncoming(packet)
        }
    }

    /** This is the flooding step: give the packet to every phone except the one it came from. */
    private fun sendToEveryone(packet: Packet, except: String?) {
        val text = packet.toJson()
        for (address in server.addresses()) {
            if (address != except) server.send(address, text)
        }
        for (address in client.addresses()) {
            if (address != except) client.send(address, text)
        }
    }

    /** A new link is ready, so introduce ourselves straight away instead of waiting for the timer. */
    private fun onLinkUp(address: String) {
        announce()
        Log.d(TAG, "link up with $address")
    }

    /** A link is gone, so that phone is no longer a direct neighbour. */
    private fun onLinkDown(address: String) {
        val id = idByAddress.remove(address) ?: return
        store.clearNearby(id)
        Log.d(TAG, "link down with $address")
    }
}

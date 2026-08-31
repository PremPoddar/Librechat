package com.example.librechat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRequestTest {

    @Test
    fun `receiving a request sets status to PENDING_RECEIVED and adds the message`() {
        val store = ChatStore()
        val request = Packet.request(from = "alice", name = "Alice", to = "bob", text = "Hello!")
        
        store.addIncoming(request)
        
        assertEquals(ChatRequestStatus.PENDING_RECEIVED, store.statusOf("alice"))
        assertEquals(1, store.messages("alice").value.size)
        assertEquals("Hello!", store.messages("alice").value[0].text)
    }

    @Test
    fun `sending a request sets status to PENDING_SENT and adds the message`() {
        val store = ChatStore()
        val request = Packet.request(from = "bob", name = "Bob", to = "alice", text = "Hi Alice!")
        
        store.addOutgoing("alice", request)
        
        assertEquals(ChatRequestStatus.PENDING_SENT, store.statusOf("alice"))
        assertEquals(1, store.messages("alice").value.size)
        assertEquals("Hi Alice!", store.messages("alice").value[0].text)
    }

    @Test
    fun `receiving an acceptance for a sent request sets status to ACCEPTED`() {
        val store = ChatStore()
        val aliceId = "alice"
        
        // Bob sends request to Alice
        store.addOutgoing(aliceId, Packet.request("bob", "Bob", aliceId, "Hi"))
        assertEquals(ChatRequestStatus.PENDING_SENT, store.statusOf(aliceId))
        
        // Bob receives acceptance from Alice
        store.addIncoming(Packet.accept(aliceId, "Alice", "bob"))
        
        assertEquals(ChatRequestStatus.ACCEPTED, store.statusOf(aliceId))
    }

    @Test
    fun `accepting a request manually updates status`() {
        val store = ChatStore()
        val aliceId = "alice"
        
        // Bob receives request from Alice
        store.addIncoming(Packet.request(aliceId, "Alice", "bob", "Hi"))
        assertEquals(ChatRequestStatus.PENDING_RECEIVED, store.statusOf(aliceId))
        
        // Bob accepts manually (e.g. via UI)
        store.updateStatus(aliceId, ChatRequestStatus.ACCEPTED)
        
        assertEquals(ChatRequestStatus.ACCEPTED, store.statusOf(aliceId))
    }
}

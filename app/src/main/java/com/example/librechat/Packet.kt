package com.example.librechat

import org.json.JSONException
import org.json.JSONObject
import kotlin.random.Random

/**
 * An announcement carrying a phone's name. Every phone sends one regularly, and they travel
 * across the mesh, so each phone knows who is still around.
 */
const val TYPE_HELLO = "hello"

/** A chat message. These are the packets that travel across the mesh. */
const val TYPE_MSG = "msg"

/** A request to start a chat. */
const val TYPE_REQUEST = "request"

/** An acceptance of a chat request. */
const val TYPE_ACCEPT = "accept"

/** A request to delete a contact. */
const val TYPE_DELETE = "delete"

/** Emergency SOS message. */
const val TYPE_SOS = "sos"

/** An empty recipient means the message is for everybody. */
const val PUBLIC = ""

/** How many hops a new message is allowed to travel before the mesh stops forwarding it. */
const val START_TTL = 5

/**
 * One unit of data sent over a Bluetooth link, encoded as JSON.
 *
 * JSON is used because it is easy to read while debugging and Android already ships a parser,
 * so the app needs no extra libraries.
 */
data class Packet(
    val type: String,
    val from: String,
    val name: String,
    val id: String = "",
    val to: String = PUBLIC,
    val text: String = "",
    val ttl: Int = START_TTL,
    val timestamp: Long = System.currentTimeMillis()
) {

    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type)
        json.put("from", from)
        json.put("name", name)
        json.put("id", id)
        json.put("to", to)
        json.put("text", text)
        json.put("ttl", ttl)
        json.put("timestamp", timestamp)
        return json.toString()
    }

    companion object {

        /** Returns null if the text is damaged or is not a packet at all, so the caller can ignore it. */
        fun fromJson(text: String): Packet? {
            return try {
                val json = JSONObject(text)
                Packet(
                    type = json.getString("type"),
                    from = json.getString("from"),
                    name = json.optString("name"),
                    id = json.optString("id"),
                    to = json.optString("to"),
                    text = json.optString("text"),
                    ttl = json.optInt("ttl"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            } catch (e: JSONException) {
                null
            }
        }

        /**
         * An announcement saying "this phone is here". It travels across the mesh like a message,
         * so it needs its own id, otherwise other phones would treat the second one as a repeat
         * of the first and throw it away.
         */
        fun hello(from: String, name: String) = Packet(
            type = TYPE_HELLO,
            from = from,
            name = name,
            id = newId(),
        )

        fun message(from: String, name: String, to: String, text: String, isSos: Boolean = false) = Packet(
            type = if (isSos) TYPE_SOS else TYPE_MSG,
            from = from,
            name = name,
            id = newId(),
            to = to,
            text = text,
            ttl = START_TTL,
        )

        fun request(from: String, name: String, to: String, text: String) = Packet(
            type = TYPE_REQUEST,
            from = from,
            name = name,
            id = newId(),
            to = to,
            text = text,
            ttl = START_TTL,
        )

        fun accept(from: String, name: String, to: String) = Packet(
            type = TYPE_ACCEPT,
            from = from,
            name = name,
            id = newId(),
            to = to,
            ttl = START_TTL,
        )

        fun delete(from: String, name: String, to: String) = Packet(
            type = TYPE_DELETE,
            from = from,
            name = name,
            id = newId(),
            to = to,
            ttl = START_TTL,
        )

        /** A random id used to recognise a message we have already forwarded. */
        fun newId(): String = randomHex(8)

        fun randomHex(length: Int): String {
            val hex = "0123456789abcdef"
            return (1..length).map { hex[Random.nextInt(hex.length)] }.joinToString("")
        }
    }
}

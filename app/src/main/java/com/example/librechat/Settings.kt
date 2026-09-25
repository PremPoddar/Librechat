package com.example.librechat

import android.content.Context

/**
 * The two things the app keeps between runs, stored with SharedPreferences, which is the small
 * key and value store Android gives every app.
 *
 * Chat messages are deliberately not kept, only who this phone is.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("librechat", Context.MODE_PRIVATE)

    /** The name the user typed on the first run. Empty means they have not chosen one yet. */
    var name: String
        get() = prefs.getString(KEY_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_NAME, value).apply()
        }

    /**
     * The id of this phone. It is made up once on the first run and then kept, so other phones
     * still recognise this one after the app is closed and opened again.
     */
    val id: String
        get() {
            val saved = prefs.getString(KEY_ID, null)
            if (saved != null) return saved

            val fresh = Packet.randomHex(4)
            prefs.edit().putString(KEY_ID, fresh).apply()
            return fresh
        }

    /**
     * A list of peers we have accepted chat requests from. They are stored as "id|name" strings
     * so they can be shown in the device list even when offline.
     */
    var pairedPeers: Set<String>
        get() = prefs.getStringSet(KEY_PAIRED, emptySet()) ?: emptySet()
        private set(value) {
            prefs.edit().putStringSet(KEY_PAIRED, value).apply()
        }

    fun addPairedPeer(id: String, name: String) {
        val current = pairedPeers.toMutableSet()
        // Remove old entry for this ID if it exists (e.g. name update)
        current.removeAll { it.startsWith("$id|") }
        current.add("$id|$name")
        pairedPeers = current
    }

    fun removePairedPeer(id: String) {
        val current = pairedPeers.toMutableSet()
        current.removeAll { it.startsWith("$id|") }
        pairedPeers = current
    }

    /**
     * Set of peer IDs that the user has archived.
     */
    var archivedPeers: Set<String>
        get() = prefs.getStringSet(KEY_ARCHIVED, emptySet()) ?: emptySet()
        private set(value) {
            prefs.edit().putStringSet(KEY_ARCHIVED, value).apply()
        }

    fun addArchivedPeer(id: String) {
        val current = archivedPeers.toMutableSet()
        current.add(id)
        archivedPeers = current
    }

    fun removeArchivedPeer(id: String) {
        val current = archivedPeers.toMutableSet()
        current.remove(id)
        archivedPeers = current
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_ID = "id"
        const val KEY_PAIRED = "paired_peers"
        const val KEY_ARCHIVED = "archived_peers"
    }
}

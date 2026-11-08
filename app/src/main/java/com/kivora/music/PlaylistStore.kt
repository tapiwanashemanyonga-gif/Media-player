package com.kivora.music

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Persists named playlists as ordered sets of local media content URIs. */
internal class PlaylistStore(
    private val preferences: SharedPreferences
) {
    fun load(): LinkedHashMap<String, MutableSet<String>> {
        val playlists = linkedMapOf<String, MutableSet<String>>()
        val saved = preferences.getString(KEY_SAVED_PLAYLISTS, "{}") ?: "{}"

        return try {
            val json = JSONObject(saved)
            val keys = json.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val array = json.optJSONArray(name) ?: JSONArray()
                val uris = linkedSetOf<String>()
                for (i in 0 until array.length()) {
                    val uri = array.optString(i)
                    if (uri.isNotBlank()) uris.add(uri)
                }
                playlists[name] = uris
            }
            playlists
        } catch (_: Exception) {
            linkedMapOf()
        }
    }

    fun save(playlists: Map<String, Set<String>>) {
        val json = JSONObject()
        playlists.forEach { (name, uris) ->
            val array = JSONArray()
            uris.forEach { array.put(it) }
            json.put(name, array)
        }
        preferences.edit().putString(KEY_SAVED_PLAYLISTS, json.toString()).apply()
    }

    private companion object {
        const val KEY_SAVED_PLAYLISTS = "saved_playlists"
    }
}

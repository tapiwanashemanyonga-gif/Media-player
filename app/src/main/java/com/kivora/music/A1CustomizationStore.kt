package com.kivora.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only personalization data. It deliberately lives outside the playback service:
 * changing a theme or home layout can never mutate a Media3 player or its queue.
 */
data class A1CustomizationSettings(
    val accentArgb: Int = 0xFFD0A2FF.toInt(),
    val secondaryAccentArgb: Int = 0xFF765397.toInt(),
    val wallpaperUri: String? = null,
    val homeSectionOrder: List<String> = DEFAULT_HOME_SECTIONS,
    val hiddenHomeSections: Set<String> = emptySet(),
    val visualizerSkin: String = "wave",
    val pinnedShortcuts: List<String> = emptyList(),
    val homeDensity: String = "comfortable",
    val homeCardShape: String = "rounded"
) {
    companion object {
        val DEFAULT_HOME_SECTIONS = listOf("recent", "albums", "playlists")
        val ALLOWED_SECTIONS = DEFAULT_HOME_SECTIONS.toSet()
        val ALLOWED_SKINS = setOf("wave", "vinyl", "cassette")
        val ALLOWED_HOME_DENSITIES = setOf("compact", "comfortable", "spacious")
        val ALLOWED_HOME_CARD_SHAPES = setOf("rounded", "soft", "square")
    }
}

data class A1ThemeProfile(
    val name: String,
    val accentArgb: Int,
    val secondaryAccentArgb: Int,
    val wallpaperUri: String? = null,
    val homeSectionOrder: List<String> = A1CustomizationSettings.DEFAULT_HOME_SECTIONS,
    val hiddenHomeSections: Set<String> = emptySet(),
    val visualizerSkin: String = "wave",
    val homeDensity: String = "comfortable",
    val homeCardShape: String = "rounded"
)

data class A1TrackMemory(
    val note: String = "",
    val mood: String = "",
    val photoUri: String? = null
)

data class A1EqualizerProfile(
    val name: String,
    val levelsMillibels: List<Int>,
    val enabled: Boolean = true
)

/**
 * Versioned SharedPreferences repository for user-owned, offline personalization.
 * All values are validated on read so damaged or older preference data falls back safely.
 */
class A1CustomizationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "a1_personalization_v1",
        Context.MODE_PRIVATE
    )

    fun load(): A1CustomizationSettings {
        val order = readStringList(KEY_HOME_ORDER)
            .filter { it in A1CustomizationSettings.ALLOWED_SECTIONS }
            .distinct()
        val hidden = readStringList(KEY_HOME_HIDDEN)
            .filter { it in A1CustomizationSettings.ALLOWED_SECTIONS }
            .toSet()
        val skin = preferences.getString(KEY_SKIN, "wave").orEmpty()
            .takeIf { it in A1CustomizationSettings.ALLOWED_SKINS } ?: "wave"
        val pins = readStringList(KEY_PINS).distinct().take(MAX_PINS)
        val density = preferences.getString(KEY_HOME_DENSITY, "comfortable").orEmpty()
            .takeIf { it in A1CustomizationSettings.ALLOWED_HOME_DENSITIES } ?: "comfortable"
        val cardShape = preferences.getString(KEY_HOME_CARD_SHAPE, "rounded").orEmpty()
            .takeIf { it in A1CustomizationSettings.ALLOWED_HOME_CARD_SHAPES } ?: "rounded"
        return A1CustomizationSettings(
            accentArgb = preferences.getInt(KEY_ACCENT, 0xFFD0A2FF.toInt()),
            secondaryAccentArgb = preferences.getInt(KEY_SECONDARY, 0xFF765397.toInt()),
            wallpaperUri = preferences.getString(KEY_WALLPAPER, null),
            homeSectionOrder = (order + A1CustomizationSettings.DEFAULT_HOME_SECTIONS)
                .distinct().filter { it in A1CustomizationSettings.ALLOWED_SECTIONS },
            hiddenHomeSections = hidden,
            visualizerSkin = skin,
            pinnedShortcuts = pins,
            homeDensity = density,
            homeCardShape = cardShape
        )
    }

    fun save(settings: A1CustomizationSettings) {
        val safeOrder = (settings.homeSectionOrder + A1CustomizationSettings.DEFAULT_HOME_SECTIONS)
            .distinct().filter { it in A1CustomizationSettings.ALLOWED_SECTIONS }
        val safeHidden = settings.hiddenHomeSections
            .filter { it in A1CustomizationSettings.ALLOWED_SECTIONS }
        val safeSkin = settings.visualizerSkin
            .takeIf { it in A1CustomizationSettings.ALLOWED_SKINS } ?: "wave"
        preferences.edit()
            .putInt(KEY_ACCENT, settings.accentArgb)
            .putInt(KEY_SECONDARY, settings.secondaryAccentArgb)
            .putString(KEY_WALLPAPER, settings.wallpaperUri)
            .putString(KEY_HOME_ORDER, JSONArray(safeOrder).toString())
            .putString(KEY_HOME_HIDDEN, JSONArray(safeHidden).toString())
            .putString(KEY_SKIN, safeSkin)
            .putString(KEY_PINS, JSONArray(settings.pinnedShortcuts.distinct().take(MAX_PINS)).toString())
            .putString(KEY_HOME_DENSITY, settings.homeDensity.takeIf { it in A1CustomizationSettings.ALLOWED_HOME_DENSITIES } ?: "comfortable")
            .putString(KEY_HOME_CARD_SHAPE, settings.homeCardShape.takeIf { it in A1CustomizationSettings.ALLOWED_HOME_CARD_SHAPES } ?: "rounded")
            .apply()
    }

    fun saveThemeProfile(profile: A1ThemeProfile) {
        val name = profile.name.trim().take(40)
        require(name.isNotBlank()) { "Give this theme a name." }
        val profiles = readObject(KEY_THEME_PROFILES)
        profiles.put(name, JSONObject().apply {
            put("accent", profile.accentArgb)
            put("secondary", profile.secondaryAccentArgb)
            put("wallpaperUri", profile.wallpaperUri)
            put("homeSectionOrder", JSONArray(profile.homeSectionOrder.filter { it in A1CustomizationSettings.ALLOWED_SECTIONS }))
            put("hiddenHomeSections", JSONArray(profile.hiddenHomeSections.filter { it in A1CustomizationSettings.ALLOWED_SECTIONS }))
            put("visualizerSkin", profile.visualizerSkin.takeIf { it in A1CustomizationSettings.ALLOWED_SKINS } ?: "wave")
            put("homeDensity", profile.homeDensity.takeIf { it in A1CustomizationSettings.ALLOWED_HOME_DENSITIES } ?: "comfortable")
            put("homeCardShape", profile.homeCardShape.takeIf { it in A1CustomizationSettings.ALLOWED_HOME_CARD_SHAPES } ?: "rounded")
        })
        preferences.edit().putString(KEY_THEME_PROFILES, profiles.toString()).apply()
    }

    fun loadThemeProfiles(): List<A1ThemeProfile> {
        val profiles = readObject(KEY_THEME_PROFILES)
        val keys = profiles.keys()
        val result = mutableListOf<A1ThemeProfile>()
        while (keys.hasNext()) {
            val name = keys.next()
            val item = profiles.optJSONObject(name) ?: continue
            result += A1ThemeProfile(
                name = name,
                accentArgb = item.optInt("accent", 0xFFD0A2FF.toInt()),
                secondaryAccentArgb = item.optInt("secondary", 0xFF765397.toInt()),
                wallpaperUri = item.optString("wallpaperUri").takeIf { it.isNotBlank() && it != "null" },
                homeSectionOrder = item.optJSONArray("homeSectionOrder")?.let { array ->
                    List(array.length()) { array.optString(it) }.filter { it in A1CustomizationSettings.ALLOWED_SECTIONS }.distinct()
                }?.ifEmpty { A1CustomizationSettings.DEFAULT_HOME_SECTIONS } ?: A1CustomizationSettings.DEFAULT_HOME_SECTIONS,
                hiddenHomeSections = item.optJSONArray("hiddenHomeSections")?.let { array ->
                    List(array.length()) { array.optString(it) }.filter { it in A1CustomizationSettings.ALLOWED_SECTIONS }.toSet()
                } ?: emptySet(),
                visualizerSkin = item.optString("visualizerSkin", "wave").takeIf { it in A1CustomizationSettings.ALLOWED_SKINS } ?: "wave",
                homeDensity = item.optString("homeDensity", "comfortable").takeIf { it in A1CustomizationSettings.ALLOWED_HOME_DENSITIES } ?: "comfortable",
                homeCardShape = item.optString("homeCardShape", "rounded").takeIf { it in A1CustomizationSettings.ALLOWED_HOME_CARD_SHAPES } ?: "rounded"
            )
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun deleteThemeProfile(name: String) {
        val profiles = readObject(KEY_THEME_PROFILES)
        profiles.remove(name)
        preferences.edit().putString(KEY_THEME_PROFILES, profiles.toString()).apply()
    }

    fun saveTrackMemory(uri: String, memory: A1TrackMemory) {
        val all = readObject(KEY_TRACK_MEMORIES)
        all.put(uri, JSONObject().apply {
            put("note", memory.note.take(MAX_NOTE_LENGTH))
            put("mood", memory.mood.take(40))
            put("photoUri", memory.photoUri)
        })
        preferences.edit().putString(KEY_TRACK_MEMORIES, all.toString()).apply()
    }

    fun loadTrackMemory(uri: String): A1TrackMemory {
        val item = readObject(KEY_TRACK_MEMORIES).optJSONObject(uri) ?: return A1TrackMemory()
        return A1TrackMemory(
            note = item.optString("note"),
            mood = item.optString("mood"),
            photoUri = item.optString("photoUri").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    fun saveEqualizerProfile(profile: A1EqualizerProfile) {
        val name = profile.name.trim().take(40)
        require(name.isNotBlank()) { "Give this sound profile a name." }
        val profiles = readObject(KEY_EQ_PROFILES)
        profiles.put(name, JSONObject().apply {
            put("levels", JSONArray(profile.levelsMillibels))
            put("enabled", profile.enabled)
        })
        preferences.edit().putString(KEY_EQ_PROFILES, profiles.toString()).apply()
    }

    fun loadEqualizerProfiles(): List<A1EqualizerProfile> {
        val profiles = readObject(KEY_EQ_PROFILES)
        return profiles.keys().asSequence().mapNotNull { name ->
            val item = profiles.optJSONObject(name) ?: return@mapNotNull null
            val levels = item.optJSONArray("levels") ?: return@mapNotNull null
            A1EqualizerProfile(
                name = name,
                levelsMillibels = List(levels.length()) { index -> levels.optInt(index) },
                enabled = item.optBoolean("enabled", true)
            )
        }.toList()
    }

    fun deleteEqualizerProfile(name: String) {
        val profiles = readObject(KEY_EQ_PROFILES)
        profiles.remove(name)
        preferences.edit().putString(KEY_EQ_PROFILES, profiles.toString()).apply()
    }

    fun saveFolderTag(uri: String, tag: String?) {
        val tags = readObject(KEY_FOLDER_TAGS)
        if (tag.isNullOrBlank()) tags.remove(uri) else tags.put(uri, tag.trim().take(40))
        preferences.edit().putString(KEY_FOLDER_TAGS, tags.toString()).apply()
    }

    fun loadFolderTags(): Map<String, String> {
        val tags = readObject(KEY_FOLDER_TAGS)
        return buildMap {
            val keys = tags.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                tags.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
    }

    private fun readStringList(key: String): List<String> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun readObject(key: String): JSONObject = runCatching {
        JSONObject(preferences.getString(key, "{}"))
    }.getOrDefault(JSONObject())

    companion object {
        private const val KEY_ACCENT = "accent_argb"
        private const val KEY_SECONDARY = "secondary_accent_argb"
        private const val KEY_WALLPAPER = "wallpaper_uri"
        private const val KEY_HOME_ORDER = "home_section_order"
        private const val KEY_HOME_HIDDEN = "home_section_hidden"
        private const val KEY_SKIN = "visualizer_skin"
        private const val KEY_PINS = "pinned_shortcuts"
        private const val KEY_HOME_DENSITY = "home_density"
        private const val KEY_HOME_CARD_SHAPE = "home_card_shape"
        private const val KEY_TRACK_MEMORIES = "track_memories"
        private const val KEY_THEME_PROFILES = "theme_profiles"
        private const val KEY_EQ_PROFILES = "equalizer_profiles"
        private const val KEY_FOLDER_TAGS = "folder_tags"
        private const val MAX_PINS = 12
        private const val MAX_NOTE_LENGTH = 2000
    }
}

package com.kivora.music

import android.net.Uri
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaQueueSelectorTest {
    private val first = song("First", "Artist A", "Album One", "content://audio/1")
    private val second = song("Second", "Artist B", "Album Two", "content://audio/2")
    private val third = song("Third", "Artist A", "Album One", "content://audio/3")

    @Test
    fun selectsAllSongsWhenNoFilterIsActive() {
        assertEquals(listOf(first, second, third), select())
    }

    @Test
    fun favoritesFilterUsesLocalUriIdentity() {
        assertEquals(listOf(second), select(favorites = setOf(second.uri.toString())))
    }

    @Test
    fun explicitPlaylistTakesPrecedenceOverFavorites() {
        assertEquals(
            listOf(third, first),
            select(playlist = listOf(third, first), favorites = setOf(second.uri.toString()))
        )
    }

    @Test
    fun albumAndArtistFiltersCanBeCombined() {
        assertEquals(
            listOf(first, third),
            select(browse = true, albumKey = MediaLibraryOrdering.albumGroupKey(first), artist = "artist a")
        )
    }

    @Test
    fun artistFilterIsCaseInsensitive() {
        assertEquals(listOf(first, third), select(browse = true, artist = "ARTIST A"))
    }

    private fun select(
        playlist: List<Song>? = null,
        favorites: Set<String> = emptySet(),
        browse: Boolean = false,
        albumKey: String? = null,
        artist: String? = null
    ) = MediaQueueSelector.select(
        allSongs = listOf(first, second, third),
        playlistSongs = playlist,
        favoritesOnly = favorites.isNotEmpty(),
        favoriteUris = favorites,
        libraryBrowse = browse,
        selectedAlbumKey = albumKey,
        selectedArtistName = artist
    )

    private fun song(title: String, artist: String, album: String, uri: String) =
        Song(title, artist, album, 1L, Uri.parse(uri), null)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaylistStoreTest {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var store: PlaylistStore

    @Before
    fun setUp() {
        preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("playlist-store-tests", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = PlaylistStore(preferences)
    }

    @Test
    fun savesAndRestoresPlaylistUrisInTheirOriginalOrder() {
        val songs = linkedSetOf("content://audio/3", "content://audio/1", "content://audio/2")
        store.save(linkedMapOf("Road trip" to songs))

        val restored = store.load()

        assertEquals(songs.toList(), restored["Road trip"]?.toList())
    }

    @Test
    fun emptyStorageLoadsAsAnEmptyPlaylistMap() {
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun malformedStoredJsonDoesNotCrashAndLoadsEmpty() {
        preferences.edit().putString("saved_playlists", "{not-json").commit()

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun blankUrisAreIgnoredAndDuplicateUrisAreDeduplicated() {
        preferences.edit()
            .putString("saved_playlists", """{"Mix":["content://audio/1","","content://audio/1","content://audio/2"]}""")
            .commit()

        val restored = store.load()["Mix"]

        assertEquals(listOf("content://audio/1", "content://audio/2"), restored?.toList())
        assertFalse(restored?.contains("") ?: true)
    }
}

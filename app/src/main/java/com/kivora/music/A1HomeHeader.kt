package com.kivora.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HomePurple = Color(0xFFC084FC)
private val HomeMuted = Color(0xFFBBAACB)

@Composable
fun A1HomeHeader(
    songCount: Int,
    albumCount: Int,
    playlistCount: Int,
    onBrowse: () -> Unit,
    onAlbums: () -> Unit,
    onArtists: () -> Unit,
    onFavourites: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "A1 FAMILY MEDIA",
                color = HomePurple,
                fontSize = 12.sp,
                letterSpacing = 2.sp
            )
            Text(
                "Your media. Your way.",
                color = Color(0xFFFFFAFF),
                fontSize = 27.sp,
                lineHeight = 32.sp
            )
            Text(
                "$songCount songs  ·  $albumCount albums  ·  $playlistCount playlists",
                color = HomeMuted,
                fontSize = 12.sp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            HomeShortcut("Songs", "Listen", Icons.Filled.MusicNote, onBrowse, Modifier.weight(1f))
            HomeShortcut("Albums", "Browse", Icons.Filled.Album, onAlbums, Modifier.weight(1f))
            HomeShortcut("Artists", "Explore", Icons.Filled.Person, onArtists, Modifier.weight(1f))
            HomeShortcut("Saved", "Favourites", Icons.Filled.Favorite, onFavourites, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HomeShortcut(
    title: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF261A35)),
        shape = RoundedCornerShape(17.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(icon, contentDescription = null, tint = HomePurple, modifier = Modifier.size(23.dp))
            Text(title, color = Color.White, fontSize = 11.sp, maxLines = 1)
            Text(detail, color = HomeMuted, fontSize = 9.sp, maxLines = 1)
        }
    }
}

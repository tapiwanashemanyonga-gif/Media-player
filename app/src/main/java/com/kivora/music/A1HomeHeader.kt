package com.kivora.music

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    MaterialTheme(colorScheme = darkColorScheme(
        primary = HomePurple,
        background = Color(0xFF110B1B),
        surface = Color(0xFF261A35),
        onSurface = Color(0xFFF9F5FF)
    )) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "A1 FAMILY MEDIA",
                    color = HomePurple,
                    fontSize = 11.sp,
                    letterSpacing = 1.8.sp
                )
                Text(
                    "Your sound, your space.",
                    color = Color(0xFFFFFAFF),
                    fontSize = 26.sp,
                    lineHeight = 31.sp
                )
                Text(
                    "Your offline collection, all in one place.",
                    color = HomeMuted,
                    fontSize = 13.sp
                )
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF302044)),
                shape = RoundedCornerShape(20.dp),
                onClick = onBrowse
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("YOUR MUSIC LIBRARY", color = HomeMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                        Text("$songCount songs", color = Color.White, fontSize = 21.sp)
                        Text("Browse your collection", color = HomeMuted, fontSize = 12.sp)
                    }
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = HomePurple, modifier = Modifier.size(34.dp))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                HomeShortcut("Albums", "$albumCount", Icons.Filled.Album, onAlbums, Modifier.weight(1f))
                HomeShortcut("Artists", "Explore", Icons.Filled.Person, onArtists, Modifier.weight(1f))
                HomeShortcut("Favourites", "Saved", Icons.Filled.Favorite, onFavourites, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HomeShortcut(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF261A35)),
        shape = RoundedCornerShape(17.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = HomePurple, modifier = Modifier.size(22.dp))
            Text(title, color = Color.White, fontSize = 11.sp, maxLines = 1)
            Text(detail, color = HomeMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

package com.kivora.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HomePurple = Color(0xFFD0A2FF)
private val HomeMuted = Color(0xFFC5B4D7)
private val HomeSurface = Color(0xFF261A36)

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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(25.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF43265F),
                            Color(0xFF2B1B40),
                            Color(0xFF1D172A)
                        )
                    )
                )
                .border(1.dp, Color(0xFF765397), RoundedCornerShape(25.dp))
                .padding(horizontal = 19.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text(
                "A1 FAMILY MEDIA  ·  YOUR LOCAL COLLECTION",
                color = HomePurple,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.15.sp
            )
            Text(
                "Your media.\nYour moment.",
                color = Color(0xFFFFFAFF),
                fontSize = 29.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "All your music and videos, together in one beautiful place.",
                color = HomeMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HomeStat(songCount.toString(), "SONGS", Modifier.weight(1f))
                HomeStat(albumCount.toString(), "ALBUMS", Modifier.weight(1f))
                HomeStat(playlistCount.toString(), "PLAYLISTS", Modifier.weight(1f))
            }
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
private fun HomeStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0x33201032))
            .padding(horizontal = 9.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = HomeMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
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
        colors = CardDefaults.cardColors(containerColor = HomeSurface),
        shape = RoundedCornerShape(17.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49345F)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(icon, contentDescription = null, tint = HomePurple, modifier = Modifier.size(22.dp))
            Text(title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(detail, color = HomeMuted, fontSize = 8.sp, maxLines = 1)
        }
    }
}

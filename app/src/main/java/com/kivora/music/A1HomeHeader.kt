package com.kivora.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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

private val HomeMuted = Color(0xFFC5B4D7)
private val HomeSurface = Color(0xFF261A36)

@Composable
fun A1HomeHeader(
    songCount: Int,
    albumCount: Int,
    playlistCount: Int,
    accentArgb: Int = 0xFFD0A2FF.toInt(),
    onBrowse: () -> Unit,
    onAlbums: () -> Unit,
    onArtists: () -> Unit,
    onFavourites: () -> Unit
) {
    val accent = Color(accentArgb)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compactHome = maxWidth < 360.dp
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compactHome) 12.dp else 15.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(if (compactHome) 21.dp else 25.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF43265F),
                                Color(0xFF2B1B40),
                                Color(0xFF1D172A)
                            )
                        )
                    )
                    .border(1.dp, Color(0xFF765397), RoundedCornerShape(if (compactHome) 21.dp else 25.dp))
                    .padding(horizontal = if (compactHome) 15.dp else 19.dp, vertical = if (compactHome) 16.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactHome) 10.dp else 13.dp)
            ) {
                Text(
                    "A1 FAMILY MEDIA  ·  YOUR LOCAL COLLECTION",
                    color = accent,
                    fontSize = if (compactHome) 8.sp else 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = if (compactHome) 0.7.sp else 1.15.sp,
                    maxLines = 1
                )
                Text(
                    "Your media.\nYour moment.",
                    color = Color(0xFFFFFAFF),
                    fontSize = if (compactHome) 26.sp else 29.sp,
                    lineHeight = if (compactHome) 30.sp else 33.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "All your music and videos, together in one beautiful place.",
                    color = HomeMuted,
                    fontSize = if (compactHome) 11.sp else 12.sp,
                    lineHeight = if (compactHome) 16.sp else 18.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compactHome) 6.dp else 8.dp)
                ) {
                    HomeStat(songCount.toString(), "SONGS", Modifier.weight(1f), compactHome)
                    HomeStat(albumCount.toString(), "ALBUMS", Modifier.weight(1f), compactHome)
                    HomeStat(playlistCount.toString(), "PLAYLISTS", Modifier.weight(1f), compactHome)
                }
            }

            if (compactHome) {
                // Two comfortable columns prevent four shortcut cards becoming cramped
                // on narrow phones and keep labels readable without horizontal scrolling.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    HomeShortcut("Songs", "Listen", Icons.Filled.MusicNote, onBrowse, Modifier.weight(1f), accent, true)
                    HomeShortcut("Albums", "Browse", Icons.Filled.Album, onAlbums, Modifier.weight(1f), accent, true)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    HomeShortcut("Artists", "Explore", Icons.Filled.Person, onArtists, Modifier.weight(1f), accent, true)
                    HomeShortcut("Saved", "Favourites", Icons.Filled.Favorite, onFavourites, Modifier.weight(1f), accent, true)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    HomeShortcut("Songs", "Listen", Icons.Filled.MusicNote, onBrowse, Modifier.weight(1f), accent)
                    HomeShortcut("Albums", "Browse", Icons.Filled.Album, onAlbums, Modifier.weight(1f), accent)
                    HomeShortcut("Artists", "Explore", Icons.Filled.Person, onArtists, Modifier.weight(1f), accent)
                    HomeShortcut("Saved", "Favourites", Icons.Filled.Favorite, onFavourites, Modifier.weight(1f), accent)
                }
            }
        }
    }
}

@Composable
private fun HomeStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0x33201032))
            .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = if (compact) 8.dp else 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(value, color = Color.White, fontSize = if (compact) 16.sp else 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = HomeMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

@Composable
private fun HomeShortcut(
    title: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFD0A2FF),
    compact: Boolean = false
) {
    Card(
        modifier = modifier
            .heightIn(min = if (compact) 86.dp else 82.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = HomeSurface),
        shape = RoundedCornerShape(if (compact) 15.dp else 17.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49345F)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (compact) 11.dp else 13.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(if (compact) 23.dp else 22.dp))
            Text(title, color = Color.White, fontSize = if (compact) 12.sp else 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(detail, color = HomeMuted, fontSize = if (compact) 9.sp else 8.sp, maxLines = 1)
        }
    }
}

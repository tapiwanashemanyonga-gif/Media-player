package com.kivora.music

import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun A1SongRow(
    song: Song,
    isFavorite: Boolean,
    isInPlaylist: Boolean,
    onArtworkReady: (ImageView) -> Unit,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onMore: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        color = Color(0xFF21172F),
        shape = RoundedCornerShape(17.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49345F)),
        tonalElevation = 0.dp
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Reduce fixed controls on narrow phones before the title column is squeezed.
            val compact = maxWidth < 340.dp
            Row(
                modifier = Modifier.padding(
                    start = if (compact) 6.dp else 8.dp,
                    end = 2.dp,
                    top = 8.dp,
                    bottom = 8.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp)
            ) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageResource(R.drawable.ic_audio_placeholder)
                        clipToOutline = true
                        onArtworkReady(this)
                    }
                },
                modifier = Modifier
                    .size(if (compact) 42.dp else 50.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = song.title,
                    color = Color(0xFFFAF7FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.artist} · ${song.album}",
                    color = Color(0xFFBEAFD2),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            IconButton(onClick = onFavorite, modifier = Modifier.size(if (compact) 28.dp else 32.dp)) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favourites" else "Add to favourites",
                    tint = if (isFavorite) Color(0xFFFACC15) else Color(0xFFAE9FC2),
                    modifier = Modifier.size(if (compact) 16.dp else 18.dp)
                )
            }

            IconButton(
                onClick = if (isInPlaylist) onRemoveFromPlaylist else onAddToPlaylist,
                modifier = Modifier.size(if (compact) 28.dp else 32.dp)
            ) {
                Icon(
                    imageVector = if (isInPlaylist) Icons.Filled.RemoveCircleOutline else Icons.Filled.PlaylistAdd,
                    contentDescription = if (isInPlaylist) "Remove from playlist" else "Add to playlist",
                    tint = if (isInPlaylist) Color(0xFFF87171) else Color(0xFFD8B4FE),
                    modifier = Modifier.size(if (compact) 17.dp else 19.dp)
                )
            }

            IconButton(onClick = onMore, modifier = Modifier.size(if (compact) 28.dp else 30.dp)) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More song actions",
                    tint = Color(0xFFD8B4FE),
                    modifier = Modifier.size(if (compact) 17.dp else 19.dp)
                )
            }
            }
        }
    }
}

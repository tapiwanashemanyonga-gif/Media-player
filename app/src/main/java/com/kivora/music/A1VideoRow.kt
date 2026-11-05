package com.kivora.music

import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
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
fun A1VideoRow(
    title: String,
    duration: String,
    isSelected: Boolean,
    onThumbnailReady: (ImageView) -> Unit,
    onPlay: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Play video $title", onClick = onPlay),
        color = if (isSelected) Color(0xFF30203F) else Color(0xFF21172F),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) Color(0xFFD0A2FF) else Color(0xFF49345F)
        ),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 100.dp, height = 70.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF39264B)),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            setImageResource(android.R.drawable.ic_media_play)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            clipToOutline = true
                            onThumbnailReady(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color(0xFFEBD9FF),
                    modifier = Modifier.size(30.dp)
                )
            }

            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    color = Color(0xFFFAF7FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = duration,
                    color = Color(0xFFC5B4D7),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

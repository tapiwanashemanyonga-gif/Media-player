package com.kivora.music

import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
        color = Color(0xFF1E152B),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) Color(0xFFAD73EC) else Color(0xFF453059)
        ),
        tonalElevation = if (isSelected) 3.dp else 1.dp
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                modifier = Modifier
                    .size(width = 104.dp, height = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    color = Color(0xFFFAF7FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = duration,
                    color = Color(0xFFBEAFD2),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }

            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = "Play $title",
                tint = Color(0xFFD8B4FE),
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

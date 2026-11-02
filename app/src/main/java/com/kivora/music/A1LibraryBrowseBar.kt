package com.kivora.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun A1LibraryBrowseBar(
    selectedMode: String,
    onModeSelected: (String) -> Unit
) {
    val options = listOf(
        "songs" to "Songs",
        "albums" to "Albums",
        "artists" to "Artists"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (mode, title) ->
            FilterChip(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                label = {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF191126),
                    labelColor = Color(0xFFBEAFD2),
                    selectedContainerColor = Color(0xFF5B3084),
                    selectedLabelColor = Color(0xFFFAEFFF)
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedMode == mode,
                    borderColor = Color(0xFF4C3563),
                    selectedBorderColor = Color(0xFFAC70E0)
                )
            )
        }
    }
}

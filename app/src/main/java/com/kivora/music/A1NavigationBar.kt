package com.kivora.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val A1Background = Color(0xFF171020)
private val A1Purple = Color(0xFFC084FC)
private val A1Muted = Color(0xFFB8A7C9)

@Composable
fun A1NavigationBar(
    selectedTab: String,
    onTabSelected: (String) -> Unit
) {
    val scheme = darkColorScheme(
        primary = A1Purple,
        onPrimary = Color.White,
        secondary = Color(0xFFD6B7FF),
        background = A1Background,
        surface = Color(0xFF21182D),
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(color = A1Background) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                NavigationBar(
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().height(68.dp),
                    containerColor = Color(0xFF21182D),
                    contentColor = Color.White,
                    tonalElevation = 0.dp
                ) {
                    val destinations = listOf(
                        Triple("home", "Home", Icons.Filled.Home),
                        Triple("search", "Search", Icons.Filled.Search),
                        Triple("library", "Library", Icons.Filled.LibraryMusic),
                        Triple("videos", "Videos", Icons.Filled.VideoLibrary)
                    )
                    destinations.forEach { (route, label, icon) ->
                        val selected = selectedTab == route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onTabSelected(route) },
                            icon = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label
                                )
                            },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 11.sp
                                )
                            },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = A1Purple,
                                indicatorColor = Color(0xFF59347C),
                                unselectedIconColor = A1Muted,
                                unselectedTextColor = A1Muted
                            )
                        )
                    }
                }
            }
        }
    }
}

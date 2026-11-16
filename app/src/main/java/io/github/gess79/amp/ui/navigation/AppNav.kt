package io.github.gess79.amp.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.gess79.amp.R
import io.github.gess79.amp.ui.components.MiniPlayerBar
import io.github.gess79.amp.ui.directories.DirectoriesScreen
import io.github.gess79.amp.ui.nowplaying.NowPlayingScreen
import io.github.gess79.amp.ui.playlists.PlaylistsScreen
import io.github.gess79.amp.ui.search.SearchScreen
import io.github.gess79.amp.ui.settings.SettingsScreen

sealed class Dest(val route: String) {
    data object Playlists : Dest("playlists")
    data object NowPlaying : Dest("now_playing")
    data object Directories : Dest("directories")
    data object Search : Dest("search")
    data object Settings : Dest("settings")
}

private data class Tab(val dest: Dest, val icon: ImageVector, val labelRes: Int)

@Composable
fun AmpRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val vm: RootViewModel = hiltViewModel()
    val playback by vm.playback.collectAsStateWithLifecycle()
    var playlistsReselectTick by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        Tab(Dest.Playlists, Icons.AutoMirrored.Filled.QueueMusic, R.string.tab_playlists),
        Tab(Dest.NowPlaying, Icons.Default.PlayCircle, R.string.tab_now_playing),
        Tab(Dest.Directories, Icons.Default.Folder, R.string.tab_directories),
        Tab(Dest.Search, Icons.Default.Search, R.string.tab_search),
    )

    fun openSettings() {
        navController.navigate(Dest.Settings.route) {
            launchSingleTop = true
        }
    }

    fun goToTab(tab: Dest) {
        if (tab == Dest.Playlists) {
            // Always surface the playlists root (from Now Playing, Settings, other tabs,
            // or a playlist detail). popBackStack to the start route pops everything above it.
            if (!navController.popBackStack(Dest.Playlists.route, inclusive = false)) {
                navController.navigate(Dest.Playlists.route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                }
            }
            playlistsReselectTick++
            return
        }
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.dest.route,
                        onClick = { goToTab(tab.dest) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Dest.Playlists.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(Dest.Playlists.route) {
                    PlaylistsScreen(
                        onOpenSettings = ::openSettings,
                        reselectTick = playlistsReselectTick,
                    )
                }
                composable(Dest.NowPlaying.route) {
                    NowPlayingScreen(onOpenSettings = ::openSettings)
                }
                composable(Dest.Directories.route) {
                    DirectoriesScreen(onOpenSettings = ::openSettings)
                }
                composable(Dest.Search.route) {
                    SearchScreen(onOpenSettings = ::openSettings)
                }
                composable(Dest.Settings.route) {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }
            }
            if (current != Dest.NowPlaying.route) {
                MiniPlayerBar(
                    state = playback,
                    onOpen = {
                        navController.navigate(Dest.NowPlaying.route) {
                            launchSingleTop = true
                        }
                    },
                    onPrevious = { vm.skipToPrevious() },
                    onPlayPause = { vm.playPause() },
                    onNext = { vm.skipToNext() },
                    onSeek = { vm.seekTo(it) },
                )
            }
        }
    }
}

package com.amp.player.ui.search

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amp.player.R
import com.amp.player.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: SearchViewModel = hiltViewModel()) {
    val title by vm.title.collectAsStateWithLifecycle()
    val artist by vm.artist.collectAsStateWithLifecycle()
    val album by vm.album.collectAsStateWithLifecycle()
    val directory by vm.directory.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var advanced by remember { mutableStateOf(false) }
    var menuForTrack by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.search_title)) },
            windowInsets = WindowInsets(0),
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            FilterChip(
                selected = advanced,
                onClick = { advanced = !advanced },
                label = {
                    Text(
                        if (advanced) {
                            stringResource(R.string.search_advanced)
                        } else {
                            stringResource(R.string.search_simple)
                        },
                    )
                },
            )
            if (!advanced) {
                OutlinedTextField(
                    value = title,
                    onValueChange = vm::setTitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_query_hint)) },
                )
            } else {
                OutlinedTextField(
                    value = title,
                    onValueChange = vm::setTitle,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_title)) },
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = vm::setArtist,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_artist)) },
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = vm::setAlbum,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_album)) },
                )
                OutlinedTextField(
                    value = directory,
                    onValueChange = vm::setDirectory,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_directory)) },
                )
            }
        }
        LazyColumn {
            items(results, key = { it.id }) { track ->
                Row {
                    TrackRow(
                        track = track,
                        onClick = { vm.playTrack(track.id) },
                        trailing = {
                            IconButton(onClick = { vm.toggleFavorite(track.id) }) {
                                Icon(
                                    Icons.Default.FavoriteBorder,
                                    contentDescription = stringResource(R.string.cd_favorite),
                                )
                            }
                            IconButton(onClick = { menuForTrack = track.id }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = stringResource(R.string.cd_add_to_playlist),
                                )
                            }
                            DropdownMenu(
                                expanded = menuForTrack == track.id,
                                onDismissRequest = { menuForTrack = null },
                            ) {
                                playlists.forEach { pl ->
                                    DropdownMenuItem(
                                        text = { Text(pl.name) },
                                        onClick = {
                                            vm.addToPlaylist(pl.id, track.id)
                                            menuForTrack = null
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

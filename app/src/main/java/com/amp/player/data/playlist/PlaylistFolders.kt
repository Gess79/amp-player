package com.amp.player.data.playlist

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.amp.player.data.settings.UserSettings
import com.amp.player.util.AmpLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class PlaylistFolder(
    val uri: Uri,
    val label: String,
    val hasAccess: Boolean,
)

/** SAF folder grants the user picked for playlist files (m3u/xspf). */
@Singleton
class PlaylistFolders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: UserSettings,
) {
    val folders: Flow<List<PlaylistFolder>> = settings.playlistFolderUris.map { stored ->
        stored.map { raw ->
            val uri = Uri.parse(raw)
            PlaylistFolder(uri, DocumentPaths.label(uri), hasAccess(uri))
        }.sortedBy { it.label.lowercase() }
    }

    /** Folders that still have a persisted read grant; only these are scanned. */
    suspend fun accessibleUris(): List<Uri> =
        settings.playlistFolderUris.first().map(Uri::parse).filter(::hasAccess)

    suspend fun add(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { AmpLog.e("Playlists", "takePersistableUriPermission failed $treeUri", it) }
        settings.addPlaylistFolder(treeUri.toString())
        settings.setPlaylistFolderPromptDone(true)
        AmpLog.i("Playlists", "folder added ${DocumentPaths.label(treeUri)}")
    }

    suspend fun remove(treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        settings.removePlaylistFolder(treeUri.toString())
        AmpLog.i("Playlists", "folder removed ${DocumentPaths.label(treeUri)}")
    }

    private fun hasAccess(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}

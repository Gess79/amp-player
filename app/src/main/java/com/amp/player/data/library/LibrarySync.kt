package com.amp.player.data.library

import com.amp.player.data.playlist.PlaylistImporter
import com.amp.player.util.AmpLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    val trackCount: Int,
    val playlistFilesImported: Int,
)

@Singleton
class LibrarySync @Inject constructor(
    private val library: LibraryRepository,
    private val playlistImporter: PlaylistImporter,
) {
    private val mutex = Mutex()

    /** [force] re-reads every playlist file; otherwise only changed files are imported. */
    suspend fun refreshAll(force: Boolean = false): SyncResult = mutex.withLock {
        val tracks = library.refreshLibrary()
        val playlists = try {
            playlistImporter.syncFromStorage(force)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AmpLog.e("Playlists", "playlist sync failed", t)
            0
        }
        SyncResult(tracks, playlists)
    }
}

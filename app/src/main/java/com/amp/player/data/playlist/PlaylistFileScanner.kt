package com.amp.player.data.playlist

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.amp.player.util.AmpLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PlaylistFile(
    val uri: Uri,
    /** Absolute path when derivable from the document id; used for relative entries. */
    val path: String?,
    val name: String,
    val format: Format,
    val lastModified: Long,
    val size: Long,
) {
    enum class Format { M3U, XSPF }

    /** Stable key in Room (`playlists.sourcePath`); same as the old filesystem scan. */
    val sourceKey: String get() = path ?: uri.toString()
}

data class PlaylistScanResult(
    val files: List<PlaylistFile>,
    /** Folders that could not be listed (grant revoked, storage removed). */
    val unreadableFolders: List<Uri>,
)

/** Lists m3u/xspf files inside SAF folder grants (including subfolders). */
@Singleton
class PlaylistFileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun findPlaylistFiles(treeUris: List<Uri>): PlaylistScanResult {
        val found = linkedMapOf<String, PlaylistFile>()
        val unreadable = mutableListOf<Uri>()
        for (tree in treeUris) {
            val ok = runCatching {
                walk(tree, DocumentsContract.getTreeDocumentId(tree), depth = 0, out = found)
            }.onFailure {
                AmpLog.e("Playlists", "scan failed ${DocumentPaths.label(tree)}", it)
            }.getOrDefault(false)
            if (!ok) unreadable += tree
        }
        AmpLog.i(
            "Playlists",
            "scan folders=${treeUris.size} files=${found.size} unreadable=${unreadable.size}",
        )
        return PlaylistScanResult(found.values.sortedBy { it.name.lowercase() }, unreadable)
    }

    private fun walk(
        tree: Uri,
        parentDocId: String,
        depth: Int,
        out: MutableMap<String, PlaylistFile>,
    ): Boolean {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val cursor = context.contentResolver.query(
            children,
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        ) ?: return false
        val subfolders = mutableListOf<String>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (c.getString(2) == Document.MIME_TYPE_DIR) {
                    if (depth < MAX_DEPTH && !name.startsWith(".")) subfolders += docId
                    continue
                }
                val format = when (name.substringAfterLast('.', "").lowercase()) {
                    "m3u", "m3u8" -> PlaylistFile.Format.M3U
                    "xspf" -> PlaylistFile.Format.XSPF
                    else -> continue
                }
                val file = PlaylistFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                    path = DocumentPaths.fromDocumentId(tree.authority, docId),
                    name = name.substringBeforeLast('.'),
                    format = format,
                    lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                    size = if (c.isNull(4)) 0L else c.getLong(4),
                )
                out.putIfAbsent(file.sourceKey, file)
            }
        }
        for (sub in subfolders) walk(tree, sub, depth + 1, out)
        return true
    }

    private companion object {
        const val MAX_DEPTH = 8
    }
}

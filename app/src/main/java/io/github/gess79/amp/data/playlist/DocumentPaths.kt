package io.github.gess79.amp.data.playlist

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * Best-effort absolute filesystem paths for SAF documents, needed to resolve relative playlist
 * entries and to match MediaStore paths. External-storage document ids
 * (`primary:Music/x.m3u`, `1234-ABCD:Music`) are not a documented contract but have been
 * stable across Android versions; unknown providers return null.
 */
object DocumentPaths {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** Picker start location for playlist folders (`Music/` on internal storage). */
    val musicFolderUri: Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Music")

    fun fromDocumentId(authority: String?, docId: String): String? = when (authority) {
        EXTERNAL_STORAGE_AUTHORITY -> {
            val volume = docId.substringBefore(':')
            val rel = docId.substringAfter(':', "").trim('/')
            val root = when {
                volume.equals("primary", ignoreCase = true) -> PRIMARY_ROOT
                volume.equals("home", ignoreCase = true) -> "$PRIMARY_ROOT/Documents"
                else -> "/storage/$volume"
            }
            if (rel.isEmpty()) root else "$root/$rel"
        }
        DOWNLOADS_AUTHORITY -> docId.takeIf { it.startsWith("raw:") }?.removePrefix("raw:")
        else -> null
    }

    /** Absolute path of a single picked or tree-child document. */
    fun ofDocument(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        val fromDocId = runCatching {
            if (!DocumentsContract.isDocumentUri(context, uri)) return@runCatching null
            fromDocumentId(uri.authority, DocumentsContract.getDocumentId(uri))
        }.getOrNull()
        if (fromDocId != null) return fromDocId
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it.startsWith("/") }
    }

    /** Absolute path of a tree URI returned by `ACTION_OPEN_DOCUMENT_TREE`. */
    fun ofTree(treeUri: Uri): String? = runCatching {
        fromDocumentId(treeUri.authority, DocumentsContract.getTreeDocumentId(treeUri))
    }.getOrNull()

    /** Short label for UI, e.g. `Music` or `1234-ABCD/Music`. */
    fun label(treeUri: Uri): String {
        val path = ofTree(treeUri)
        if (path != null) {
            return when {
                path == PRIMARY_ROOT -> "/"
                path.startsWith("$PRIMARY_ROOT/") -> path.removePrefix("$PRIMARY_ROOT/")
                path.startsWith("/storage/") -> path.removePrefix("/storage/")
                else -> path
            }
        }
        return runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrDefault(treeUri.toString())
    }
}

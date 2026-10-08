package com.amp.player.data.playlist

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Xml
import com.amp.player.R
import com.amp.player.data.db.PlaylistDao
import com.amp.player.data.db.PlaylistEntity
import com.amp.player.data.db.PlaylistFileStateDao
import com.amp.player.data.db.PlaylistFileStateEntity
import com.amp.player.data.db.TrackEntity
import com.amp.player.data.library.LibraryRepository
import com.amp.player.data.settings.AppLocale
import com.amp.player.util.AmpLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val playlistDao: PlaylistDao,
    private val scanner: PlaylistFileScanner,
    private val folders: PlaylistFolders,
    private val fileStateDao: PlaylistFileStateDao,
) {
    private fun str(): Context = AppLocale.wrap(context)

    suspend fun importUri(uri: Uri, playlistName: String? = null): ImportResult =
        withContext(Dispatchers.IO) {
            val displayName = queryDisplayName(uri)
            val name = playlistName?.ifBlank { null }
                ?: displayName?.substringBeforeLast('.')?.ifBlank { null }
                ?: str().getString(R.string.playlist_import_default_name)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext ImportResult(
                    0,
                    0,
                    str().getString(R.string.playlist_import_unreadable),
                )
            val format = detectFormat(displayName, context.contentResolver.getType(uri), bytes)
            val entries = ByteArrayInputStream(bytes).use { input ->
                when (format) {
                    PlaylistFile.Format.M3U -> parseM3u(input)
                    PlaylistFile.Format.XSPF -> parseXspf(input)
                }
            }
            // Same sourcePath as the storage scan when the real path is known, so a manual
            // import and a later scan update one playlist instead of creating two.
            val filePath = DocumentPaths.ofDocument(context, uri)
            upsertMatched(
                sourcePath = filePath ?: "uri:$uri",
                name = name,
                entryPaths = entries,
                baseDir = filePath?.let { File(it).parentFile },
            )
        }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun detectFormat(
        displayName: String?,
        mimeType: String?,
        bytes: ByteArray,
    ): PlaylistFile.Format {
        when (displayName?.substringAfterLast('.', "")?.lowercase()) {
            "xspf" -> return PlaylistFile.Format.XSPF
            "m3u", "m3u8" -> return PlaylistFile.Format.M3U
        }
        if (mimeType?.contains("xspf", ignoreCase = true) == true) return PlaylistFile.Format.XSPF
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return if (head.startsWith("<")) PlaylistFile.Format.XSPF else PlaylistFile.Format.M3U
    }

    /**
     * Scans the user's SAF playlist folders for .m3u/.m3u8/.xspf and syncs them into Room.
     * Files whose date/size and the track library are unchanged are skipped unless [force].
     * Returns the number of file playlists that are up to date.
     */
    suspend fun syncFromStorage(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        val trees = folders.accessibleUris()
        val scan = scanner.findPlaylistFiles(trees)
        val tracks = library.getAllTracks()
        val librarySignature = librarySignature(tracks)
        val known = fileStateDao.getAll().associateBy { it.sourceKey }
        var imported = 0
        var skipped = 0
        for (file in scan.files) {
            val state = known[file.sourceKey]
            val unchanged = !force && state != null && file.lastModified > 0 &&
                state.lastModified == file.lastModified &&
                state.size == file.size &&
                state.librarySignature == librarySignature
            if (unchanged && playlistDao.getBySourcePath(file.sourceKey) != null) {
                skipped++
                continue
            }
            val result = runCatching { importFile(file, tracks) }
                .onFailure { AmpLog.e("Playlists", "import failed ${file.name}", it) }
                .getOrNull() ?: continue
            if (result.error == null && result.matched > 0) imported++
            if (result.listed > 0) {
                fileStateDao.upsert(
                    PlaylistFileStateEntity(
                        sourceKey = file.sourceKey,
                        lastModified = file.lastModified,
                        size = file.size,
                        librarySignature = librarySignature,
                    ),
                )
            }
        }
        val removed = removeDeletedPlaylists(trees, scan)
        AmpLog.i(
            "Playlists",
            "sync force=$force files=${scan.files.size} imported=$imported unchanged=$skipped " +
                "removed=$removed",
        )
        imported + skipped
    }

    /**
     * Deletes file playlists whose file vanished from a scanned folder. Before deleting, the
     * document is looked up directly (the scan skips dot-folders and very deep folders).
     * Nothing is deleted while any folder is unreadable — a missing SD card must not wipe
     * playlists. Manual imports from outside the folders (`uri:` keys) are never touched.
     */
    private suspend fun removeDeletedPlaylists(
        trees: List<Uri>,
        scan: PlaylistScanResult,
    ): Int {
        if (trees.isEmpty() || scan.unreadableFolders.isNotEmpty()) return 0
        val present = scan.files.mapTo(HashSet()) { it.sourceKey }
        var removed = 0
        for (playlist in library.getFilePlaylists()) {
            val key = playlist.sourcePath ?: continue
            if (key in present) continue
            val documentUri = trees.firstNotNullOfOrNull { documentUriInTree(it, key) } ?: continue
            if (documentMayExist(documentUri)) continue
            library.deletePlaylist(playlist.id)
            fileStateDao.delete(key)
            removed++
            AmpLog.i("Playlists", "removed deleted file playlist ${playlist.name}")
        }
        return removed
    }

    /** Document URI of [sourceKey] inside [tree], or null if the key lies outside that folder. */
    private fun documentUriInTree(tree: Uri, sourceKey: String): Uri? {
        if (sourceKey.startsWith("$tree/document/")) return Uri.parse(sourceKey)
        val treePath = DocumentPaths.ofTree(tree) ?: return null
        if (!sourceKey.startsWith("$treePath/")) return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return null
        val docId = treeDocId.trimEnd('/') + sourceKey.removePrefix(treePath)
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    }

    /** False only when the provider clearly reports the document as missing. */
    private fun documentMayExist(uri: Uri): Boolean = try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } ?: false
    } catch (e: SecurityException) {
        true
    } catch (e: Exception) {
        false
    }

    private fun librarySignature(tracks: List<TrackEntity>): Long =
        tracks.sortedBy { it.id }.fold(17L) { acc, t -> (acc * 31 + t.id) * 31 + t.path.hashCode() }

    suspend fun importFile(
        file: PlaylistFile,
        tracks: List<TrackEntity>? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        val entries = runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                when (file.format) {
                    PlaylistFile.Format.M3U -> parseM3u(input)
                    PlaylistFile.Format.XSPF -> parseXspf(input)
                }
            }
        }.getOrNull() ?: return@withContext ImportResult(
            0,
            0,
            str().getString(R.string.playlist_import_unreadable_named, file.name),
        )
        upsertMatched(
            sourcePath = file.sourceKey,
            name = file.name,
            entryPaths = entries,
            baseDir = file.path?.let { File(it).parentFile },
            tracks = tracks,
        )
    }

    private suspend fun upsertMatched(
        sourcePath: String,
        name: String,
        entryPaths: List<String>,
        baseDir: File?,
        tracks: List<TrackEntity>? = null,
    ): ImportResult {
        if (entryPaths.isEmpty()) {
            return ImportResult(0, 0, str().getString(R.string.playlist_import_empty))
        }
        val all = tracks ?: library.getAllTracks()
        val matched = matchTracks(entryPaths, all, baseDir)
        if (matched.isEmpty()) {
            return ImportResult(
                0,
                entryPaths.size,
                str().getString(R.string.playlist_import_no_matches, name),
            )
        }
        val existing = playlistDao.getBySourcePath(sourcePath)
        val playlistId = if (existing != null) {
            playlistDao.rename(existing.id, name)
            existing.id
        } else {
            playlistDao.upsert(
                PlaylistEntity(
                    name = name,
                    sourcePath = sourcePath,
                    isFavorites = false,
                ),
            )
        }
        library.setPlaylistTracks(playlistId, matched)
        return ImportResult(matched.size, entryPaths.size, null, playlistId)
    }

    private fun matchTracks(
        entries: List<String>,
        all: List<TrackEntity>,
        baseDir: File?,
    ): List<Long> {
        val byPath = HashMap<String, Long>(all.size * 2)
        val byName = HashMap<String, MutableList<Long>>()
        for (track in all) {
            byPath[normalizePath(track.path)] = track.id
            val fileName = track.path.substringAfterLast('/').lowercase()
            byName.getOrPut(fileName) { mutableListOf() }.add(track.id)
        }

        val matched = LinkedHashSet<Long>()
        for (raw in entries) {
            val candidates = expandCandidates(raw, baseDir)
            var found: Long? = null
            for (candidate in candidates) {
                found = byPath[normalizePath(candidate)]
                if (found != null) break
            }
            val entry = decodeEntry(raw)
            if (found == null && !entry.startsWith("/")) {
                found = matchRelativeSuffix(entry, all)
            }
            if (found == null) {
                val fileName = entry.substringAfterLast('/').lowercase()
                found = byName[fileName]?.singleOrNull() ?: byName[fileName]?.firstOrNull()
            }
            if (found != null) matched += found
        }
        return matched.toList()
    }

    /**
     * Relative entry without a known base folder: pick the track whose path ends with the
     * same folder/file tail (e.g. `Album/01.mp3`). Leading `./`, `../` and Windows drive
     * letters (`C:`) are dropped.
     */
    private fun matchRelativeSuffix(entry: String, all: List<TrackEntity>): Long? {
        val tail = entry.split('/')
            .dropWhile {
                it == "." || it == ".." || it.isEmpty() || (it.length == 2 && it[1] == ':')
            }
            .joinToString("/")
            .lowercase()
        if (!tail.contains('/')) return null
        val suffix = "/$tail"
        return all.firstOrNull { normalizePath(it.path).endsWith(suffix) }?.id
    }

    /**
     * `file:` URIs and percent-encoding are decoded; plain paths keep a literal `+`.
     * A `%` that is not a valid escape (e.g. `100% Você.mp3`) is part of the file name.
     */
    private fun decodeEntry(raw: String): String {
        val trimmed = raw.trim()
        val decoded = when {
            trimmed.startsWith("file:", ignoreCase = true) ->
                runCatching { Uri.parse(trimmed).path }.getOrNull()
                    ?: trimmed.removePrefix("file://")
            isPercentEncoded(trimmed) -> Uri.decode(trimmed)
            else -> trimmed
        }
        return decoded.replace('\\', '/').replace("\u0000", "")
    }

    private fun isPercentEncoded(value: String): Boolean {
        if (!value.contains('%')) return false
        var i = value.indexOf('%')
        while (i >= 0) {
            if (i + 2 >= value.length || !value[i + 1].isHexDigit() || !value[i + 2].isHexDigit()) {
                return false
            }
            i = value.indexOf('%', i + 3)
        }
        return true
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun expandCandidates(raw: String, baseDir: File?): List<String> {
        val entry = decodeEntry(raw)

        val out = mutableListOf(entry)
        if (!entry.startsWith("/") && baseDir != null) {
            val file = File(baseDir, entry)
            runCatching { file.canonicalFile.absolutePath }.getOrNull()?.let { out += it }
            out += file.absolutePath
        }
        // Common mount prefix variants
        if (entry.startsWith("/sdcard/")) {
            out += entry.replaceFirst("/sdcard/", "/storage/emulated/0/")
        }
        if (entry.startsWith("/storage/emulated/0/")) {
            out += entry.replaceFirst("/storage/emulated/0/", "/sdcard/")
        }
        return out.distinct()
    }

    private fun normalizePath(path: String): String =
        path.trim().replace('\\', '/').lowercase()

    private fun parseM3u(input: InputStream): List<String> {
        val paths = mutableListOf<String>()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim().removePrefix("\uFEFF")
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    paths += trimmed
                }
            }
        }
        return paths
    }

    private fun parseXspf(input: InputStream): List<String> {
        val paths = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(InputStreamReader(input, Charsets.UTF_8))
        var event = parser.eventType
        var inLocation = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.equals("location", ignoreCase = true)) inLocation = true
                }
                XmlPullParser.TEXT -> {
                    if (inLocation) {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) paths += text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("location", ignoreCase = true)) inLocation = false
                }
            }
            event = parser.next()
        }
        return paths
    }

    data class ImportResult(
        val matched: Int,
        val listed: Int,
        val error: String? = null,
        val playlistId: Long? = null,
    )
}

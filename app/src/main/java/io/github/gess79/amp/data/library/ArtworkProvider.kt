package io.github.gess79.amp.data.library

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import io.github.gess79.amp.util.AmpLog
import java.io.File
import java.io.FileNotFoundException

/**
 * Cover art per track: the picture embedded in the file, otherwise the folder image, but only for
 * folders that hold a single album. MediaStore album art is not used directly because Android
 * applies a folder's `AlbumArt_*.jpg` / `Folder.jpg` to every track without an embedded picture,
 * so mixed folders (favorites, compilations) all show the same unrelated cover.
 *
 * Exported read-only: System UI media controls and Bluetooth load the session's artwork URI from
 * their own process.
 */
class ArtworkProvider : ContentProvider() {

    companion object {
        private const val PARAM_VERSION = "v"
        private const val PARAM_FOLDER_ART = "folder"
        private const val PARAM_ALBUM_ID = "album"
        private const val FOLDER_ART_SIZE = 512

        fun uriFor(
            context: Context,
            trackId: Long,
            dateModified: Long,
            albumId: Long,
            allowFolderArt: Boolean,
        ): String {
            val builder = Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.artwork")
                .appendPath("track")
                .appendPath(trackId.toString())
                .appendQueryParameter(PARAM_VERSION, dateModified.toString())
            if (allowFolderArt) {
                builder.appendQueryParameter(PARAM_FOLDER_ART, "1")
                    .appendQueryParameter(PARAM_ALBUM_ID, albumId.toString())
            }
            return builder.build().toString()
        }
    }

    private val locks = Array(16) { Any() }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val context = context ?: throw FileNotFoundException("no context")
        val trackId = uri.pathSegments.getOrNull(1)?.toLongOrNull()
            ?: throw FileNotFoundException("bad uri $uri")
        val version = uri.getQueryParameter(PARAM_VERSION) ?: "0"
        val allowFolderArt = uri.getQueryParameter(PARAM_FOLDER_ART) == "1"
        val albumId = uri.getQueryParameter(PARAM_ALBUM_ID)?.toLongOrNull()

        val dir = File(context.cacheDir, "artwork").apply { mkdirs() }
        val base = "$trackId-$version-${if (allowFolderArt) "f" else "e"}"
        val image = File(dir, "$base.img")
        val none = File(dir, "$base.none")

        synchronized(locks[Math.floorMod(trackId, locks.size.toLong()).toInt()]) {
            if (!image.exists() && !none.exists()) {
                dir.listFiles { f -> f.name.startsWith("$trackId-") }?.forEach { it.delete() }
                val bytes = embeddedPicture(context, trackId)
                    ?: if (allowFolderArt) folderPicture(context, trackId, albumId) else null
                if (bytes != null) {
                    val tmp = File(dir, "$base.tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(image)
                } else {
                    none.createNewFile()
                }
            }
        }
        if (!image.exists()) throw FileNotFoundException("no artwork for $trackId")
        return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun audioUri(trackId: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)

    private fun embeddedPicture(context: Context, trackId: Long): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, audioUri(trackId))
            retriever.embeddedPicture
        } catch (t: Throwable) {
            AmpLog.w("Artwork", "embedded picture failed id=$trackId: ${t.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Android's per-track thumbnail: embedded picture or an image file in the track's own folder. */
    private fun folderPicture(context: Context, trackId: Long, albumId: Long?): ByteArray? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bitmap = context.contentResolver.loadThumbnail(
                    audioUri(trackId),
                    Size(FOLDER_ART_SIZE, FOLDER_ART_SIZE),
                    null,
                )
                java.io.ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.toByteArray()
                }
            } else {
                val id = albumId ?: return null
                val art = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    id,
                )
                context.contentResolver.openInputStream(art)?.use { it.readBytes() }
            }
        }.getOrNull()

    override fun getType(uri: Uri): String = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

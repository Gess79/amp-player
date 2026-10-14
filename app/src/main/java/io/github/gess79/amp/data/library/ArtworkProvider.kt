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
import android.util.Base64
import android.util.Size
import io.github.gess79.amp.util.AmpLog
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cover art per track: the picture embedded in the file, otherwise the folder image, but only for
 * folders that hold a single album. MediaStore album art is not used directly because Android
 * applies a folder's `AlbumArt_*.jpg` / `Folder.jpg` to every track without an embedded picture,
 * so mixed folders (favorites, compilations) all show the same unrelated cover.
 *
 * Exported read-only: System UI media controls and Bluetooth load the session's artwork URI from
 * their own process. Every URI carries an HMAC made with a per-install secret, so other apps can
 * only open URIs this app handed out, not probe arbitrary MediaStore IDs (which would bypass
 * READ_MEDIA_AUDIO) or fill the cache.
 */
class ArtworkProvider : ContentProvider() {

    companion object {
        private const val PARAM_VERSION = "v"
        private const val PARAM_FOLDER_ART = "folder"
        private const val PARAM_ALBUM_ID = "album"
        private const val PARAM_TOKEN = "t"
        private const val FOLDER_ART_SIZE = 512
        private const val PREFS = "artwork_provider"
        private const val PREF_SECRET = "secret"

        @Volatile
        private var secret: ByteArray? = null

        private fun secret(context: Context): ByteArray {
            secret?.let { return it }
            synchronized(this) {
                secret?.let { return it }
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val stored = prefs.getString(PREF_SECRET, null)
                    ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                val key = stored ?: ByteArray(32).also { bytes ->
                    SecureRandom().nextBytes(bytes)
                    prefs.edit().putString(PREF_SECRET, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
                }
                secret = key
                return key
            }
        }

        private fun token(
            context: Context,
            trackId: Long,
            version: String,
            allowFolderArt: Boolean,
            albumId: String?,
        ): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret(context), "HmacSHA256"))
            val payload = "$trackId|$version|${if (allowFolderArt) 1 else 0}|${albumId.orEmpty()}"
            val digest = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(digest, 0, 16, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

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
            val album = if (allowFolderArt) albumId.toString() else null
            builder.appendQueryParameter(
                PARAM_TOKEN,
                token(context, trackId, dateModified.toString(), allowFolderArt, album),
            )
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
        val albumParam = uri.getQueryParameter(PARAM_ALBUM_ID)
        val expected = token(context, trackId, version, allowFolderArt, albumParam)
        val given = uri.getQueryParameter(PARAM_TOKEN).orEmpty()
        if (!MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())) {
            throw SecurityException("invalid artwork token")
        }
        val albumId = albumParam?.toLongOrNull()

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

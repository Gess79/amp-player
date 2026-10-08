package com.amp.player.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun loadWidgetArtwork(context: Context, artworkUri: String?, sizePx: Int = 128): Bitmap? =
    withContext(Dispatchers.IO) {
        if (artworkUri.isNullOrBlank()) return@withContext null
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(artworkUri))?.use { stream ->
                val decoded = BitmapFactory.decodeStream(stream) ?: return@use null
                Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true)
            }
        }.getOrNull()
    }

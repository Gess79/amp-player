package io.github.gess79.amp.ui.nowplaying

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.sqrt

/**
 * Drops a flat frame (black bars, or a hairline around them) from cover art.
 * Trims a pair of edges only when both sides are a frame, so a dark picture
 * is not shifted by cropping one side.
 */
object TrimNearBlackEdges : Transformation {
    override val cacheKey: String = "trim-near-black-edges-v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val source = when (input.config) {
            Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565 -> input
            else -> input.copy(Bitmap.Config.ARGB_8888, false) ?: return input
        }
        val width = source.width
        val height = source.height
        if (width < 8 || height < 8) return input
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val top = borderWidth(height) { y -> isFlat(pixels, width, y * width, SAMPLE_STEP, 1) }
        val bottom = borderWidth(height) { y ->
            isFlat(pixels, width, (height - 1 - y) * width, SAMPLE_STEP, 1)
        }
        val trimTop = if (top > 0 && bottom > 0) top else 0
        val trimBottom = if (top > 0 && bottom > 0) bottom else 0
        val y0 = trimTop
        val y1 = height - trimBottom

        val left = borderWidth(width) { x ->
            isFlat(pixels, y1 - y0, y0 * width + x, SAMPLE_STEP, width)
        }
        val right = borderWidth(width) { x ->
            isFlat(pixels, y1 - y0, y0 * width + (width - 1 - x), SAMPLE_STEP, width)
        }
        val trimLeft = if (left > 0 && right > 0) left else 0
        val trimRight = if (left > 0 && right > 0) right else 0

        if (trimLeft == 0 && trimRight == 0 && trimTop == 0 && trimBottom == 0) return input
        val cropW = width - trimLeft - trimRight
        val cropH = height - trimTop - trimBottom
        if (cropW < 32 || cropH < 32) return input
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, -trimLeft.toFloat(), -trimTop.toFloat(), null)
        return out
    }

    private fun borderWidth(length: Int, isBorder: (Int) -> Boolean): Int {
        val max = (length * MAX_FRACTION).toInt().coerceAtMost(length / 2)
        var i = 0
        while (i < max && isBorder(i)) i++
        return i
    }

    /** True when the samples are almost one flat color (black bar, white hairline, gray edge). */
    private fun isFlat(pixels: IntArray, count: Int, start: Int, step: Int, stride: Int): Boolean {
        if (count <= 0) return false
        var n = 0
        var chroma = 0
        var sum = 0.0
        var sumSq = 0.0
        var i = 0
        while (i < count) {
            val color = pixels[start + i * stride]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            if (Color.alpha(color) >= 16) {
                if (maxOf(r, g, b) - minOf(r, g, b) > CHROMA) chroma++
                val lum = (r + g + b) / 3.0
                sum += lum
                sumSq += lum * lum
            }
            n++
            i += step
        }
        if (n == 0) return false
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        return chroma.toFloat() / n < CHROMA_FRACTION && sqrt(variance) < MAX_STD
    }

    private const val SAMPLE_STEP = 2
    private const val CHROMA = 18
    private const val CHROMA_FRACTION = 0.15f
    private const val MAX_STD = 28.0
    private const val MAX_FRACTION = 0.22f
}

package com.example.flyff

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class BuffTimerReading(
    val rawText: String,
    val remainingSeconds: Int?,
    val fromCache: Boolean = false
)

/**
 * On-device ML Kit OCR reader tuned for Flyff Universe's small bright-text buff timers
 * (e.g. "14:11", "02:00", "00:48", "4 Hr", "1 Day") with local countdown smoothing so a
 * single noisy OCR frame never triggers a premature buff recast.
 */
class BuffTimerOcrReader {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private data class CachedTimer(
        val verifiedSeconds: Int,
        val verifiedAtMs: Long,
        val consecutiveMisses: Int = 0
    )

    private val timerCache = ConcurrentHashMap<String, CachedTimer>()

    suspend fun readTimerFromStrip(
        timerStrip: Bitmap,
        cacheKey: String? = null
    ): BuffTimerReading = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val prepared = preprocessTimerStrip(timerStrip)
        val rawText = try {
            recognizeText(prepared)
        } catch (_: Throwable) {
            ""
        } finally {
            if (prepared !== timerStrip && !prepared.isRecycled) {
                prepared.recycle()
            }
        }

        val parsed = parseFlyffTimerSeconds(rawText)
        if (parsed != null) {
            if (cacheKey != null) {
                timerCache[cacheKey] = CachedTimer(
                    verifiedSeconds = parsed,
                    verifiedAtMs = now,
                    consecutiveMisses = 0
                )
            }
            return@withContext BuffTimerReading(
                rawText = rawText.trim(),
                remainingSeconds = parsed,
                fromCache = false
            )
        }

        // Smooth over transient OCR misses using monotonic elapsed countdown
        if (cacheKey != null) {
            val cached = timerCache[cacheKey]
            if (cached != null) {
                val elapsedSec = ((now - cached.verifiedAtMs) / 1000L).toInt().coerceAtLeast(0)
                if (elapsedSec <= MAX_CACHE_EXTRAPOLATION_SEC && cached.consecutiveMisses < MAX_CONSECUTIVE_MISSES) {
                    timerCache[cacheKey] = cached.copy(consecutiveMisses = cached.consecutiveMisses + 1)
                    val estimated = (cached.verifiedSeconds - elapsedSec).coerceAtLeast(1)
                    return@withContext BuffTimerReading(
                        rawText = rawText.trim(),
                        remainingSeconds = estimated,
                        fromCache = true
                    )
                }
            }
        }

        BuffTimerReading(
            rawText = rawText.trim(),
            remainingSeconds = null,
            fromCache = false
        )
    }

    fun clearCache(cacheKey: String? = null) {
        if (cacheKey == null) {
            timerCache.clear()
        } else {
            timerCache.remove(cacheKey)
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) {
                        cont.resume(result.text ?: "")
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) {
                        cont.resume("")
                    }
                }
        }

    /**
     * Upscales tiny buff timer strip 3.5x with white margins and isolates Flyff's bright
     * white/yellow outlined digits onto a crisp black-on-white canvas for ML Kit.
     */
    private fun preprocessTimerStrip(src: Bitmap): Bitmap {
        val scale = 4
        val scaledW = (src.width * scale).coerceIn(48, 360)
        val scaledH = (src.height * scale).coerceIn(28, 160)
        val upscaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        val pad = 16
        val outW = scaledW + pad * 2
        val outH = scaledH + pad * 2
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        val pixels = IntArray(scaledW * scaledH)
        upscaled.getPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)

        // Adaptive brightness threshold based on top 18% brightest pixels in the strip
        val lumas = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            lumas[i] = (r * 30 + g * 59 + b * 11) / 100
        }
        val sorted = lumas.sortedArray()
        val p85 = sorted[(sorted.size * 0.85f).toInt().coerceIn(0, sorted.lastIndex)]
        val threshold = p85.coerceIn(145, 210)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = lumas[i]
            val isBrightText = luma >= threshold || (r >= 195 && g >= 180 && b >= 110)
            pixels[i] = if (isBrightText) Color.BLACK else Color.WHITE
        }

        val binarized = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
        binarized.setPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)
        canvas.drawBitmap(binarized, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

        upscaled.recycle()
        binarized.recycle()
        return result
    }

    companion object {
        private const val MAX_CACHE_EXTRAPOLATION_SEC = 18
        private const val MAX_CONSECUTIVE_MISSES = 6

        private val MM_SS_REGEX = Regex("""(\d{1,2})\s*[:;.,]\s*(\d{2})""")
        private val FOUR_DIGIT_REGEX = Regex("""(?<!\d)(\d{2})(\d{2})(?!\d)""")
        private val HOUR_REGEX = Regex("""(\d{1,2})\s*h(?:r|rs|our)?""", RegexOption.IGNORE_CASE)
        private val DAY_REGEX = Regex("""(\d{1,2})\s*d(?:ay|ays)?""", RegexOption.IGNORE_CASE)
        private val SEC_REGEX = Regex("""(\d{1,2})\s*s(?:ec)?""", RegexOption.IGNORE_CASE)

        fun parseFlyffTimerSeconds(raw: String): Int? {
            if (raw.isBlank()) return null
            val cleaned = raw
                .replace('\n', ' ')
                .replace('O', '0')
                .replace('o', '0')
                .replace('I', '1')
                .replace('l', '1')
                .replace('|', '1')
                .replace('S', '5')
                .replace('B', '8')
                .trim()

            DAY_REGEX.find(raw)?.let { m ->
                val days = m.groupValues[1].toIntOrNull() ?: return@let
                if (days in 1..99) return days * 86400
            }
            HOUR_REGEX.find(raw)?.let { m ->
                val hrs = m.groupValues[1].toIntOrNull() ?: return@let
                if (hrs in 1..99) return hrs * 3600
            }
            MM_SS_REGEX.find(cleaned)?.let { m ->
                val mins = m.groupValues[1].toIntOrNull() ?: return@let
                val secs = m.groupValues[2].toIntOrNull() ?: return@let
                if (mins in 0..99 && secs in 0..59) {
                    return mins * 60 + secs
                }
            }
            // Sometimes ":" is dropped on tiny pixel fonts leaving e.g. "1411" or "0200"
            FOUR_DIGIT_REGEX.find(cleaned)?.let { m ->
                val mins = m.groupValues[1].toIntOrNull() ?: return@let
                val secs = m.groupValues[2].toIntOrNull() ?: return@let
                if (mins in 0..60 && secs in 0..59) {
                    return mins * 60 + secs
                }
            }
            SEC_REGEX.find(cleaned)?.let { m ->
                val secs = m.groupValues[1].toIntOrNull() ?: return@let
                if (secs in 0..99) return secs
            }
            return null
        }

        fun formatSecondsShort(seconds: Int?): String {
            if (seconds == null) return "--:--"
            if (seconds <= 0) return "0:00"
            if (seconds >= 86400) return "${seconds / 86400}d"
            if (seconds >= 3600) return "${seconds / 3600}h"
            val mins = seconds / 60
            val secs = seconds % 60
            return "$mins:${secs.toString().padStart(2, '0')}"
        }
    }
}

package com.example.flyff

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.model.WebViewTarget
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class IconAutoMatch(
    val label: String,
    val recommendedCategory: SkillCategory,
    val confidencePct: Int,
    val sourceTag: String, // "API" or "Key Match"
    val matchedSkillKeyId: String? = null
)

/**
 * Multi-cue visual icon matcher that automatically identifies Flyff Universe taskbar skills
 * AND Mobile UI active buff icons without requiring the user to pick names from a list:
 *
 * 1. Computes compact, illumination-invariant descriptors for every icon:
 *    - Full inner-78% zero-mean unit-norm RGB vector (strips outer taskbar/buff borders)
 *    - Upper-60% zero-mean unit-norm RGB vector (avoids bottom green MM:SS buff timers!)
 *    - 4x4 HSV spatial color grid (robust to gamma/JPEG compression)
 *    - 8x8 Sobel gradient magnitude silhouette (captures shape even under slight tinting)
 * 2. Matches taskbar icons against cached official Flyff skill PNGs from api.flyff.com.
 * 3. Matches active buff icons against BOTH saved Step 2 skill key icons (works 100% offline!)
 *    AND cached official Flyff skill PNGs (upper-60% clean art comparison).
 */
object FlyffIconMatcher {

    private data class IconDescriptor(
        val fullRgb: FloatArray,     // 12x12x3 = 432 zero-mean unit-norm
        val topRgb: FloatArray,      // 12x7x3 = 252 zero-mean unit-norm (upper 58% clean art)
        val topHueGrid: FloatArray,  // 4x2x12 = 96 spatial HSV histogram (upper 58%)
        val fullHueGrid: FloatArray, // 4x4x12 = 192 spatial HSV histogram (full inner)
        val topEdge: FloatArray,     // 8x5 = 40 normalized gradient magnitudes (upper 58%)
        val fullEdge: FloatArray     // 8x8 = 64 normalized gradient magnitudes (full inner)
    )

    private val fileDescriptorCache = ConcurrentHashMap<String, IconDescriptor>()

    fun invalidateCatalogCache() {
        fileDescriptorCache.clear()
    }

    /**
     * Automatically identifies a cropped taskbar icon (Step 2) using cached official Flyff skill PNGs.
     */
    fun identifyTaskbarSkillIcon(
        crop: Bitmap,
        catalogEntries: List<FlyffCatalogEntry>,
        owner: WebViewTarget
    ): IconAutoMatch? {
        if (crop.isRecycled || crop.width < 10 || crop.height < 10) return null
        val cachedEntries = catalogEntries.filter { !it.cachedIconPath.isNullOrBlank() }
        if (cachedEntries.isEmpty()) return null

        val query = extractDescriptor(crop)
        var bestEntry: FlyffCatalogEntry? = null
        var bestScore = 0f
        var secondScore = 0f

        for (entry in cachedEntries) {
            val path = entry.cachedIconPath ?: continue
            val ref = loadDescriptorForFile(path) ?: continue
            var score = compareFull(query, ref)
            // Tiny tie-breaker bonus for Assist/RM skills on A2 Buffer and fighter skills on A1
            if (owner == WebViewTarget.ACCOUNT_2 && entry.group == FlyffSkillGroup.ASSIST_RM) {
                score += 0.015f
            }
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                bestEntry = entry
            } else if (score > secondScore) {
                secondScore = score
            }
        }

        val winner = bestEntry ?: return null
        val pct = (bestScore * 100f).toInt().coerceIn(0, 100)
        val margin = bestScore - secondScore
        if (pct >= 68 || (pct >= 58 && margin >= 0.045f)) {
            return IconAutoMatch(
                label = winner.name,
                recommendedCategory = winner.recommendedCategory,
                confidencePct = pct,
                sourceTag = "Auto"
            )
        }
        return null
    }

    /**
     * Automatically identifies a cropped Mobile UI / Original View active buff icon (Step 3) by
     * comparing its upper-art region against BOTH:
     *  1. The user's already-scanned Step 2 skill key icons (works 100% offline & handles custom scrolls/items!)
     *  2. Cached official Flyff Universe skill PNGs (dynamic online catalog)
     */
    fun identifyActiveBuffIcon(
        fullTileBitmap: Bitmap,
        artCropBitmap: Bitmap,
        catalogEntries: List<FlyffCatalogEntry>,
        savedKeys: List<ScannedSkillKey>,
        templatePathById: Map<String, String>
    ): IconAutoMatch? {
        if (fullTileBitmap.isRecycled || artCropBitmap.isRecycled) return null
        // Extract descriptor from full tile (its topRgb/topHueGrid/topEdge automatically inspect upper 58% clean art)
        val query = extractDescriptor(fullTileBitmap)

        // Source 1 (Priority Tier 1): Direct cross-match against the user's already-scanned Step 2 Buff/Skill keys
        var bestKeyMatch: IconAutoMatch? = null
        var bestKeyRawScore = 0f
        var secondKeyRawScore = 0f

        val buffFirstKeys = savedKeys.sortedByDescending {
            it.category == SkillCategory.PARTY_BUFF || it.category == SkillCategory.SELF_BUFF
        }
        for (key in buffFirstKeys) {
            // Skip consumables/attacks when identifying active buff bar icons unless nothing else matches
            if (key.category == SkillCategory.HP_POT ||
                key.category == SkillCategory.MP_POT ||
                key.category == SkillCategory.FP_POT ||
                key.category == SkillCategory.ATTACK_AOE ||
                key.category == SkillCategory.HEAL
            ) continue
            val tplId = key.iconTemplateId ?: continue
            val path = templatePathById[tplId] ?: continue
            val ref = loadDescriptorForFile(path) ?: continue
            val rawScore = compareUpperArt(query, ref)
            if (rawScore > bestKeyRawScore) {
                secondKeyRawScore = bestKeyRawScore
                bestKeyRawScore = rawScore
                bestKeyMatch = IconAutoMatch(
                    label = key.label,
                    recommendedCategory = key.category,
                    confidencePct = (rawScore * 100f).toInt().coerceIn(0, 100),
                    sourceTag = "Key Match",
                    matchedSkillKeyId = key.id
                )
            } else if (rawScore > secondKeyRawScore) {
                secondKeyRawScore = rawScore
            }
        }

        // Source 2 (Priority Tier 2): Official Flyff Universe API catalog — STRICTLY buff skills only!
        // Never match non-buff attack/proc skills (e.g. Electric Counter, Electric Shock) onto active buff slots!
        var bestApiMatch: IconAutoMatch? = null
        var bestApiScore = 0f
        var secondApiScore = 0f

        for (entry in catalogEntries) {
            if (!entry.isBuff || entry.isHeal) continue
            if (entry.recommendedCategory != SkillCategory.PARTY_BUFF &&
                entry.recommendedCategory != SkillCategory.SELF_BUFF
            ) continue
            val path = entry.cachedIconPath ?: continue
            val ref = loadDescriptorForFile(path) ?: continue
            val rawScore = compareUpperArt(query, ref)
            // Mild priority boost for classic Assist/RM party buffs
            val boost = if (entry.group == FlyffSkillGroup.ASSIST_RM) 0.02f else 0f
            val score = (rawScore + boost).coerceAtMost(1f)
            if (score > bestApiScore) {
                secondApiScore = bestApiScore
                bestApiScore = score
                bestApiMatch = IconAutoMatch(
                    label = entry.name,
                    recommendedCategory = entry.recommendedCategory,
                    confidencePct = (rawScore * 100f).toInt().coerceIn(0, 100),
                    sourceTag = "Auto"
                )
            } else if (score > secondApiScore) {
                secondApiScore = score
            }
        }

        // Prefer a strong Step 2 Key Match unless a buff-only API entry decisively beats it by >= 7%
        val keyMargin = bestKeyRawScore - secondKeyRawScore
        val keyQualified = bestKeyMatch != null && (
            bestKeyRawScore >= 0.65f || (bestKeyRawScore >= 0.56f && keyMargin >= 0.04f)
            )
        if (keyQualified && (bestApiMatch == null || bestApiScore < bestKeyRawScore + 0.07f)) {
            return bestKeyMatch
        }

        val apiMargin = bestApiScore - secondApiScore
        val apiQualified = bestApiMatch != null && (
            bestApiMatch.confidencePct >= 66 || (bestApiMatch.confidencePct >= 57 && apiMargin >= 0.04f)
            )
        if (apiQualified) {
            return bestApiMatch
        }

        return if (keyQualified) bestKeyMatch else null
    }

    /**
     * Direct similarity (0..100%) between two cropped icons (used for visual auto-linking in Step 4).
     */
    fun compareIconFilesUpperArt(pathA: String, pathB: String): Int {
        val descA = loadDescriptorForFile(pathA) ?: return 0
        val descB = loadDescriptorForFile(pathB) ?: return 0
        return (compareUpperArt(descA, descB) * 100f).toInt().coerceIn(0, 100)
    }

    private fun loadDescriptorForFile(path: String): IconDescriptor? {
        fileDescriptorCache[path]?.let { return it }
        val file = File(path)
        if (!file.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        return try {
            if (bmp.width < 8 || bmp.height < 8) return null
            val desc = extractDescriptor(bmp)
            fileDescriptorCache[path] = desc
            desc
        } finally {
            bmp.recycle()
        }
    }

    private fun compareFull(a: IconDescriptor, b: IconDescriptor): Float {
        val rgbNcc = ((dot(a.fullRgb, b.fullRgb) + 1f) * 0.5f).coerceIn(0f, 1f)
        val hsvCos = dot(a.fullHueGrid, b.fullHueGrid).coerceIn(0f, 1f)
        val edgeCos = dot(a.fullEdge, b.fullEdge).coerceIn(0f, 1f)
        return rgbNcc * 0.52f + hsvCos * 0.30f + edgeCos * 0.18f
    }

    private fun compareUpperArt(a: IconDescriptor, b: IconDescriptor): Float {
        val rgbNcc = ((dot(a.topRgb, b.topRgb) + 1f) * 0.5f).coerceIn(0f, 1f)
        val hsvCos = dot(a.topHueGrid, b.topHueGrid).coerceIn(0f, 1f)
        val edgeCos = dot(a.topEdge, b.topEdge).coerceIn(0f, 1f)
        return rgbNcc * 0.54f + hsvCos * 0.28f + edgeCos * 0.18f
    }

    private fun extractDescriptor(src: Bitmap): IconDescriptor {
        // Inner 78% crop trims outer borders (dark buff border vs cream/gold taskbar frame)
        val insetX = max(1, (src.width * 0.11f).toInt()).coerceAtMost(src.width / 4)
        val insetY = max(1, (src.height * 0.11f).toInt()).coerceAtMost(src.height / 4)
        val innerL = insetX
        val innerT = insetY
        val innerW = (src.width - insetX * 2).coerceAtLeast(4)
        val innerH = (src.height - insetY * 2).coerceAtLeast(4)

        // Upper 58% of the full icon (with small side/top inset) avoids bottom MM:SS green timer digits
        val topH = max(4, (src.height * 0.56f).toInt() - insetY).coerceAtMost(innerH)

        val fullRgb = sampleNormalizedRgb(src, innerL, innerT, innerW, innerH, 12, 12)
        val topRgb = sampleNormalizedRgb(src, innerL, innerT, innerW, topH, 12, 7)
        val fullHue = sampleSpatialHsvHistogram(src, innerL, innerT, innerW, innerH, 4, 4)
        val topHue = sampleSpatialHsvHistogram(src, innerL, innerT, innerW, topH, 4, 2)
        val fullEdge = sampleEdgeEnergy(src, innerL, innerT, innerW, innerH, 8, 8)
        val topEdge = sampleEdgeEnergy(src, innerL, innerT, innerW, topH, 8, 5)

        return IconDescriptor(
            fullRgb = fullRgb,
            topRgb = topRgb,
            topHueGrid = topHue,
            fullHueGrid = fullHue,
            topEdge = topEdge,
            fullEdge = fullEdge
        )
    }

    private fun sampleNormalizedRgb(
        bmp: Bitmap,
        left: Int,
        top: Int,
        w: Int,
        h: Int,
        gw: Int,
        gh: Int
    ): FloatArray {
        val vec = FloatArray(gw * gh * 3)
        var idx = 0
        var sum = 0.0
        for (gy in 0 until gh) {
            val y = (top + ((gy + 0.5f) * h / gh).toInt()).coerceIn(0, bmp.height - 1)
            for (gx in 0 until gw) {
                val x = (left + ((gx + 0.5f) * w / gw).toInt()).coerceIn(0, bmp.width - 1)
                val p = bmp.getPixel(x, y)
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                vec[idx++] = r
                vec[idx++] = g
                vec[idx++] = b
                sum += (r + g + b)
            }
        }
        val mean = (sum / vec.size).toFloat()
        var normSq = 0.0
        for (i in vec.indices) {
            val v = vec[i] - mean
            vec[i] = v
            normSq += (v * v)
        }
        val norm = sqrt(normSq).toFloat().coerceAtLeast(1e-4f)
        for (i in vec.indices) vec[i] /= norm
        return vec
    }

    private fun sampleSpatialHsvHistogram(
        bmp: Bitmap,
        left: Int,
        top: Int,
        w: Int,
        h: Int,
        cellsX: Int,
        cellsY: Int
    ): FloatArray {
        // 12 bins per cell: 10 hue bins (36 deg each) + 1 dark bin + 1 bright/desat bin
        val binsPerCell = 12
        val hist = FloatArray(cellsX * cellsY * binsPerCell)
        val hsv = FloatArray(3)

        for (cy in 0 until cellsY) {
            val y0 = top + (cy * h) / cellsY
            val y1 = top + ((cy + 1) * h) / cellsY
            for (cx in 0 until cellsX) {
                val x0 = left + (cx * w) / cellsX
                val x1 = left + ((cx + 1) * w) / cellsX
                val base = (cy * cellsX + cx) * binsPerCell
                for (y in y0 until y1) {
                    val sy = y.coerceIn(0, bmp.height - 1)
                    for (x in x0 until x1) {
                        val sx = x.coerceIn(0, bmp.width - 1)
                        Color.colorToHSV(bmp.getPixel(sx, sy), hsv)
                        val bin = when {
                            hsv[2] < 0.22f -> 10
                            hsv[1] < 0.20f -> 11
                            else -> ((hsv[0] / 36f).toInt()).coerceIn(0, 9)
                        }
                        hist[base + bin] += 1f
                    }
                }
            }
        }
        var normSq = 0.0
        for (v in hist) normSq += (v * v)
        val norm = sqrt(normSq).toFloat().coerceAtLeast(1e-4f)
        for (i in hist.indices) hist[i] /= norm
        return hist
    }

    private fun sampleEdgeEnergy(
        bmp: Bitmap,
        left: Int,
        top: Int,
        w: Int,
        h: Int,
        gw: Int,
        gh: Int
    ): FloatArray {
        val luma = FloatArray(gw * gh)
        for (gy in 0 until gh) {
            val y = (top + ((gy + 0.5f) * h / gh).toInt()).coerceIn(0, bmp.height - 1)
            for (gx in 0 until gw) {
                val x = (left + ((gx + 0.5f) * w / gw).toInt()).coerceIn(0, bmp.width - 1)
                val p = bmp.getPixel(x, y)
                luma[gy * gw + gx] = 0.299f * ((p shr 16) and 0xFF) +
                    0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)
            }
        }
        val edges = FloatArray(gw * gh)
        var normSq = 0.0
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val l = luma[gy * gw + max(0, gx - 1)]
                val r = luma[gy * gw + min(gw - 1, gx + 1)]
                val u = luma[max(0, gy - 1) * gw + gx]
                val d = luma[min(gh - 1, gy + 1) * gw + gx]
                val mag = abs(r - l) + abs(d - u)
                edges[gy * gw + gx] = mag
                normSq += (mag * mag)
            }
        }
        val norm = sqrt(normSq).toFloat().coerceAtLeast(1e-4f)
        for (i in edges.indices) edges[i] /= norm
        return edges
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}

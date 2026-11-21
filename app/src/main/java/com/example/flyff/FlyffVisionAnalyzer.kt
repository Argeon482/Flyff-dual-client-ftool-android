package com.example.flyff

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.model.WebViewTarget
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class DetectedTaskbarSlot(
    val owner: WebViewTarget,
    val sectionIndex: Int, // 0, 1, 2 across bottom taskbar
    val slotIndex: Int,    // 0..9 -> Keys 1..9, 0
    val bounds: Rect,
    val iconBitmap: Bitmap
)

data class TaskbarScanResult(
    val owner: WebViewTarget,
    val taskbarBandRect: Rect,
    val detectedSlots: List<DetectedTaskbarSlot>,
    val sectionCounts: List<Int>
)

data class DetectedBuffTile(
    val owner: WebViewTarget,
    val row: Int,
    val col: Int,
    val tileBounds: Rect,
    val fullTileBitmap: Bitmap,
    val artCropBitmap: Bitmap,
    val timerStripBitmap: Bitmap,
    val detectedTimerSec: Int? = null,
    val detectedTimerRaw: String = ""
)

/**
 * Vision utilities tailored specifically to Flyff Universe:
 * - Fast (< 1ms) HP/MP/FP bar fill-% measurement robust to centered numeric overlay text
 * - Auto-detection of Mobile UI top-left HP/MP/FP bars and Active Buff icon grid
 * - Auto-detection & slot cropping of Original View (Desktop UI) bottom taskbar slots
 */
object FlyffVisionAnalyzer {

    /**
     * Measures 0..100% fill of a calibrated HP, MP, or FP bar.
     * Scans right-to-left across a 5-row vertical band to locate the rightmost filled
     * cluster so white numeric text (e.g. "12803 / 12803") drawn over the bar never
     * drags down the measured percentage.
     */
    fun measureBarFillPercent(frame: Bitmap, bar: BarCalibrationSpec): Int {
        val rect = bar.rect.toAndroidRect(frame.width, frame.height)
        val width = rect.width()
        val height = rect.height()
        if (width < 8 || height < 2) return 100

        val centerY = (rect.top + rect.bottom) / 2
        val yStart = (centerY - max(1, height / 4)).coerceIn(0, frame.height - 1)
        val yEnd = (centerY + max(1, height / 4)).coerceIn(yStart, frame.height - 1)
        val hsv = FloatArray(3)

        val columnFilled = BooleanArray(width)
        var filledColumnCount = 0

        for (col in 0 until width) {
            val x = (rect.left + col).coerceIn(0, frame.width - 1)
            var hit = false
            for (y in yStart..yEnd) {
                val pixel = frame.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                if (matchesVitalColor(bar.vitalType, hsv[0], hsv[1], hsv[2], bar.minSaturation, bar.minValue)) {
                    hit = true
                    break
                }
            }
            columnFilled[col] = hit
            if (hit) filledColumnCount++
        }

        if (filledColumnCount <= 2) return 0

        // Find rightmost sustained filled run (at least 2 of 3 consecutive columns colored)
        var rightmostCol = -1
        for (col in (width - 1) downTo 2) {
            val count = (if (columnFilled[col]) 1 else 0) +
                (if (columnFilled[col - 1]) 1 else 0) +
                (if (columnFilled[col - 2]) 1 else 0)
            if (count >= 2) {
                rightmostCol = col
                break
            }
        }
        if (rightmostCol < 0) {
            return ((filledColumnCount * 100f) / width.toFloat()).toInt().coerceIn(0, 100)
        }
        val pct = (((rightmostCol + 1).toFloat() / width.toFloat()) * 100f).toInt()
        return pct.coerceIn(0, 100)
    }

    fun matchesVitalColor(
        type: VitalType,
        hue: Float,
        sat: Float,
        value: Float,
        minSat: Float = 0.28f,
        minVal: Float = 0.24f
    ): Boolean {
        if (sat < minSat || value < minVal) return false
        return when (type) {
            VitalType.HP -> hue <= 26f || hue >= 332f
            VitalType.MP -> hue in 175f..262f
            VitalType.FP -> hue in 62f..168f
        }
    }

    /**
     * Auto-locates Mobile UI HP, MP, and FP bars + Active Buff Grid ROI inside a character's pane.
     */
    fun autoDetectMobileHudBars(
        frame: Bitmap,
        paneRect: Rect,
        owner: WebViewTarget
    ): CharacterHudRegions {
        val safePane = clampRect(paneRect, frame.width, frame.height)
        val paneW = safePane.width().coerceAtLeast(100)
        val paneH = safePane.height().coerceAtLeast(100)

        // Search top-left HUD area (x: 4%..34% of pane width, y: 0%..18% of pane height)
        val searchLeft = safePane.left + (paneW * 0.04f).toInt()
        val searchRight = safePane.left + (paneW * 0.34f).toInt()
        val searchTop = safePane.top + (paneH * 0.005f).toInt()
        val searchBottom = safePane.top + (paneH * 0.18f).toInt()

        val hpFound = findHorizontalVitalBar(frame, searchLeft, searchTop, searchRight, searchBottom, VitalType.HP)
        val mpSearchTop = hpFound?.bottom ?: (safePane.top + (paneH * 0.025f).toInt())
        val mpFound = findHorizontalVitalBar(
            frame,
            searchLeft,
            mpSearchTop,
            searchRight,
            (mpSearchTop + (paneH * 0.08f).toInt()).coerceAtMost(searchBottom + 40),
            VitalType.MP
        )
        val fpSearchTop = mpFound?.bottom ?: (mpSearchTop + (paneH * 0.025f).toInt())
        val fpFound = findHorizontalVitalBar(
            frame,
            searchLeft,
            fpSearchTop,
            searchRight,
            (fpSearchTop + (paneH * 0.08f).toInt()).coerceAtMost(searchBottom + 80),
            VitalType.FP
        )

        // Harmonize horizontal left/right alignment across whichever bars were detected
        val detectedBars = listOfNotNull(hpFound, mpFound, fpFound)
        val alignedLeft = detectedBars.minOfOrNull { it.left } ?: (safePane.left + (paneW * 0.075f).toInt())
        val alignedRight = detectedBars.maxOfOrNull { it.right } ?: (safePane.left + (paneW * 0.17f).toInt())
        val defaultBarH = detectedBars.map { it.height() }.average().takeIf { !it.isNaN() && it >= 4.0 }?.toInt()
            ?: max(10, (paneH * 0.015f).toInt())

        val hpRect = hpFound?.let { Rect(alignedLeft, it.top, alignedRight, it.bottom) }
            ?: Rect(
                alignedLeft,
                safePane.top + (paneH * 0.018f).toInt(),
                alignedRight,
                safePane.top + (paneH * 0.018f).toInt() + defaultBarH
            )
        val mpRect = mpFound?.let { Rect(alignedLeft, it.top, alignedRight, it.bottom) }
            ?: Rect(alignedLeft, hpRect.bottom + 3, alignedRight, hpRect.bottom + 3 + defaultBarH)
        val fpRect = fpFound?.let { Rect(alignedLeft, it.top, alignedRight, it.bottom) }
            ?: Rect(alignedLeft, mpRect.bottom + 3, alignedRight, mpRect.bottom + 3 + defaultBarH)

        // Mobile UI active buff grid sits directly below the HP/MP/FP header + "Buff xN" row
        val hudRightEdge = max(alignedRight + (paneW * 0.04f).toInt(), safePane.left + (paneW * 0.24f).toInt())
        val buffLeft = safePane.left + (paneW * 0.004f).toInt()
        val buffTop = (fpRect.bottom + (paneH * 0.028f).toInt()).coerceAtMost(safePane.bottom - 60)
        val buffRight = max(hudRightEdge, safePane.left + (paneW * 0.22f).toInt()).coerceAtMost(safePane.right)
        val buffBottom = (buffTop + (paneH * 0.20f).toInt()).coerceAtMost(safePane.bottom)

        val estTile = ((buffRight - buffLeft) / 7).coerceIn(24, 96)

        return CharacterHudRegions(
            owner = owner,
            paneRect = RectSpec.fromPixels(safePane.left, safePane.top, safePane.right, safePane.bottom, frame.width, frame.height),
            hpBar = BarCalibrationSpec(
                vitalType = VitalType.HP,
                rect = RectSpec.fromPixels(hpRect.left, hpRect.top, hpRect.right, hpRect.bottom, frame.width, frame.height)
            ),
            mpBar = BarCalibrationSpec(
                vitalType = VitalType.MP,
                rect = RectSpec.fromPixels(mpRect.left, mpRect.top, mpRect.right, mpRect.bottom, frame.width, frame.height)
            ),
            fpBar = BarCalibrationSpec(
                vitalType = VitalType.FP,
                rect = RectSpec.fromPixels(fpRect.left, fpRect.top, fpRect.right, fpRect.bottom, frame.width, frame.height)
            ),
            buffGridRegion = RectSpec.fromPixels(buffLeft, buffTop, buffRight, buffBottom, frame.width, frame.height),
            estimatedTileWidth = estTile,
            estimatedTileHeight = estTile
        )
    }

    private fun findHorizontalVitalBar(
        frame: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        vitalType: VitalType
    ): Rect? {
        val safeL = left.coerceIn(0, frame.width - 1)
        val safeR = right.coerceIn(safeL + 1, frame.width)
        val safeT = top.coerceIn(0, frame.height - 1)
        val safeB = bottom.coerceIn(safeT + 1, frame.height)
        val hsv = FloatArray(3)

        val rowCounts = IntArray(safeB - safeT)
        val rowFirstX = IntArray(safeB - safeT) { -1 }
        val rowLastX = IntArray(safeB - safeT) { -1 }

        for (y in safeT until safeB) {
            val rowIdx = y - safeT
            var hits = 0
            var first = -1
            var last = -1
            for (x in safeL until safeR) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                if (matchesVitalColor(vitalType, hsv[0], hsv[1], hsv[2], minSat = 0.34f, minVal = 0.28f)) {
                    hits++
                    if (first < 0) first = x
                    last = x
                }
            }
            rowCounts[rowIdx] = hits
            rowFirstX[rowIdx] = first
            rowLastX[rowIdx] = last
        }

        val maxRowHits = rowCounts.maxOrNull() ?: 0
        if (maxRowHits < 14) return null

        val threshold = max(10, (maxRowHits * 0.45f).toInt())
        var bestStart = -1
        var bestEnd = -1
        var bestScore = 0
        var curStart = -1
        var curScore = 0

        for (i in rowCounts.indices) {
            if (rowCounts[i] >= threshold) {
                if (curStart < 0) curStart = i
                curScore += rowCounts[i]
                if (curScore > bestScore) {
                    bestScore = curScore
                    bestStart = curStart
                    bestEnd = i
                }
            } else {
                curStart = -1
                curScore = 0
            }
        }
        if (bestStart < 0 || bestEnd < bestStart) return null

        var minX = Int.MAX_VALUE
        var maxX = -1
        for (i in bestStart..bestEnd) {
            if (rowFirstX[i] in 0 until minX) minX = rowFirstX[i]
            if (rowLastX[i] > maxX) maxX = rowLastX[i]
        }
        if (minX >= maxX || (maxX - minX) < 16) return null

        return Rect(minX, safeT + bestStart, maxX + 1, safeT + bestEnd + 1)
    }

    /**
     * Auto-segments the Flyff Mobile UI active buff icon grid (7 icons per row) inside `hud.buffGridRegion`,
     * filtering out empty cells and returning each occupied tile split into:
     * - fullTileBitmap (preview)
     * - artCropBitmap (upper ~62% free of timer text -> template matching)
     * - timerStripBitmap (lower ~44% -> ML Kit OCR)
     */
    fun autoDetectActiveBuffTiles(
        frame: Bitmap,
        hud: CharacterHudRegions,
        columnsPerRow: Int = 7,
        maxRows: Int = 3
    ): List<DetectedBuffTile> {
        val roi = hud.buffGridRegion.toAndroidRect(frame.width, frame.height)
        if (roi.width() < 70 || roi.height() < 30) return emptyList()

        // Find the top edge of the first buff row by scanning row-wise colorfulness/edge energy
        val hsv = FloatArray(3)
        val rowEnergy = FloatArray(roi.height())
        for (ry in 0 until roi.height()) {
            val y = roi.top + ry
            var satSum = 0f
            var samples = 0
            var x = roi.left
            while (x < roi.right) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                if (hsv[1] > 0.24f && hsv[2] > 0.18f) satSum += hsv[1]
                samples++
                x += 2
            }
            rowEnergy[ry] = if (samples > 0) satSum / samples else 0f
        }

        // Skip top header text ("Buff x14") if present by checking where tile density starts
        val tileSize = (roi.width().toFloat() / columnsPerRow.toFloat()).toInt().coerceIn(22, 110)
        var startOffsetY = 0
        val peakRowEnergy = rowEnergy.maxOrNull() ?: 0f
        if (peakRowEnergy > 0.05f) {
            val trigger = peakRowEnergy * 0.35f
            for (i in 0 until (roi.height() - tileSize / 2).coerceAtLeast(1)) {
                if (rowEnergy[i] >= trigger) {
                    startOffsetY = i
                    break
                }
            }
        }

        val tiles = mutableListOf<DetectedBuffTile>()
        val stepX = roi.width().toFloat() / columnsPerRow.toFloat()
        val rowPitch = (tileSize * 1.12f).toInt().coerceAtLeast(tileSize + 2)

        for (row in 0 until maxRows) {
            val tileTop = roi.top + startOffsetY + row * rowPitch
            val tileBottom = (tileTop + tileSize).coerceAtMost(roi.bottom)
            if (tileBottom - tileTop < tileSize * 0.72f) break

            var rowFoundAny = false
            for (col in 0 until columnsPerRow) {
                val tileLeft = (roi.left + col * stepX).toInt()
                val tileRight = (tileLeft + tileSize).coerceAtMost(roi.right)
                val rect = Rect(tileLeft, tileTop, tileRight, tileBottom)
                if (rect.width() < 18 || rect.height() < 18) continue

                val stats = analyzeTileVisualEnergy(frame, rect)
                // Active Flyff buff icons have rich internal contrast & saturation compared to empty scene/quest text
                if (stats.isOccupiedBuffTile) {
                    rowFoundAny = true
                    val fullBmp = safeCrop(frame, rect) ?: continue
                    val artInset = max(1, rect.width() / 14)
                    val artRect = Rect(
                        rect.left + artInset,
                        rect.top + artInset,
                        (rect.right - artInset).coerceAtLeast(rect.left + artInset + 6),
                        (rect.top + (rect.height() * 0.62f).toInt()).coerceAtLeast(rect.top + artInset + 6)
                    )
                    val timerRect = Rect(
                        rect.left,
                        (rect.top + (rect.height() * 0.54f).toInt()).coerceAtMost(rect.bottom - 8),
                        rect.right,
                        rect.bottom
                    )
                    val artBmp = safeCrop(frame, artRect) ?: fullBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val timerBmp = safeCrop(frame, timerRect) ?: fullBmp.copy(Bitmap.Config.ARGB_8888, false)

                    tiles.add(
                        DetectedBuffTile(
                            owner = hud.owner,
                            row = row,
                            col = col,
                            tileBounds = rect,
                            fullTileBitmap = fullBmp,
                            artCropBitmap = artBmp,
                            timerStripBitmap = timerBmp
                        )
                    )
                }
            }
            if (row > 0 && !rowFoundAny) break
        }
        return tiles
    }

    /**
     * Auto-detects Flyff Original View's bottom taskbar inside `paneRect`, segments up to 3 horizontal
     * 10-slot sections (30 slots total), and auto-crops every occupied skill/item slot while skipping
     * empty gray-dot slots.
     */
    fun autoDetectOriginalViewTaskbarSlots(
        frame: Bitmap,
        paneRect: Rect,
        owner: WebViewTarget
    ): TaskbarScanResult {
        val safePane = clampRect(paneRect, frame.width, frame.height)
        val paneW = safePane.width().coerceAtLeast(100)
        val paneH = safePane.height().coerceAtLeast(100)

        // Flyff Original View taskbar sits flush along the bottom ~6-8% of the pane
        val scanTop = (safePane.bottom - (paneH * 0.11f).toInt()).coerceAtLeast(safePane.top)
        val scanBottom = safePane.bottom

        // Locate the bright cream/ivory horizontal bar strip
        var barTop = (scanBottom - (paneH * 0.042f).toInt()).coerceAtLeast(scanTop)
        var barBottom = scanBottom
        val hsv = FloatArray(3)
        val brightRowRatio = FloatArray((scanBottom - scanTop).coerceAtLeast(1))

        for (y in scanTop until scanBottom) {
            var brightCount = 0
            var total = 0
            var x = safePane.left + (paneW * 0.12f).toInt()
            val xEnd = safePane.left + (paneW * 0.86f).toInt()
            while (x < xEnd) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                if (hsv[2] >= 0.72f) brightCount++
                total++
                x += 3
            }
            brightRowRatio[y - scanTop] = if (total > 0) brightCount.toFloat() / total.toFloat() else 0f
        }

        // Find bottom-most continuous bright horizontal strip
        var foundTop = -1
        var foundBottom = -1
        for (i in brightRowRatio.lastIndex downTo 0) {
            if (brightRowRatio[i] >= 0.35f) {
                if (foundBottom < 0) foundBottom = scanTop + i
                foundTop = scanTop + i
            } else if (foundBottom >= 0 && (foundBottom - (scanTop + i)) >= 12) {
                break
            }
        }
        if (foundTop >= 0 && foundBottom - foundTop >= 14) {
            barTop = foundTop
            barBottom = foundBottom
        }

        val barH = (barBottom - barTop).coerceAtLeast(16)
        // In Flyff Original View:
        // Left ~14.5% = [Start] + [↑Page↓]; Right ~14% = [Action Slot] + [Chat]
        // Middle ~71.5% contains 3 horizontal sections of 10 square slots each.
        val slotsAreaLeft = safePane.left + (paneW * 0.145f).toInt()
        val slotsAreaRight = safePane.left + (paneW * 0.855f).toInt()
        val totalSlotsW = (slotsAreaRight - slotsAreaLeft).coerceAtLeast(30)
        val sectionCount = 3
        val sectionWidth = totalSlotsW.toFloat() / sectionCount.toFloat()

        val detected = mutableListOf<DetectedTaskbarSlot>()
        val perSectionCounts = MutableList(sectionCount) { 0 }

        for (section in 0 until sectionCount) {
            val secLeft = slotsAreaLeft + section * sectionWidth
            val slotW = sectionWidth / 10f
            for (slot in 0 until 10) {
                val sl = (secLeft + slot * slotW).toInt()
                val sr = (sl + slotW).toInt().coerceAtMost(safePane.right)
                val insetX = max(1, ((sr - sl) * 0.08f).toInt())
                val insetY = max(1, (barH * 0.08f).toInt())
                val slotRect = Rect(
                    (sl + insetX).coerceIn(safePane.left, safePane.right - 2),
                    (barTop + insetY).coerceIn(safePane.top, safePane.bottom - 2),
                    (sr - insetX).coerceIn(sl + insetX + 2, safePane.right),
                    (barBottom - insetY).coerceIn(barTop + insetY + 2, safePane.bottom)
                )
                if (slotRect.width() < 8 || slotRect.height() < 8) continue

                val stats = analyzeTileVisualEnergy(frame, slotRect)
                if (stats.isOccupiedTaskbarSlot) {
                    val crop = safeCrop(frame, slotRect) ?: continue
                    detected.add(
                        DetectedTaskbarSlot(
                            owner = owner,
                            sectionIndex = section,
                            slotIndex = slot,
                            bounds = slotRect,
                            iconBitmap = crop
                        )
                    )
                    perSectionCounts[section] = perSectionCounts[section] + 1
                }
            }
        }

        return TaskbarScanResult(
            owner = owner,
            taskbarBandRect = Rect(slotsAreaLeft, barTop, slotsAreaRight, barBottom),
            detectedSlots = detected,
            sectionCounts = perSectionCounts
        )
    }

    fun cropTimerStripFromMatch(
        frame: Bitmap,
        matchCenterX: Int,
        matchCenterY: Int,
        artWidth: Int,
        artHeight: Int,
        fullTileHeightHint: Int
    ): Bitmap? {
        val tileW = max(artWidth + 4, fullTileHeightHint)
        val tileH = max(fullTileHeightHint, (artHeight / 0.60f).toInt())
        val tileTop = matchCenterY - artHeight / 2 - 2
        val tileLeft = matchCenterX - tileW / 2
        val timerTop = (tileTop + (tileH * 0.54f).toInt()).coerceIn(0, frame.height - 8)
        val timerBottom = (tileTop + tileH + 2).coerceIn(timerTop + 6, frame.height)
        val left = (tileLeft - 2).coerceIn(0, frame.width - 8)
        val right = (tileLeft + tileW + 2).coerceIn(left + 6, frame.width)
        return safeCrop(frame, Rect(left, timerTop, right, timerBottom))
    }

    private data class TileEnergyStats(
        val meanSat: Float,
        val lumaStdDev: Float,
        val colorfulPixelRatio: Float,
        val isOccupiedTaskbarSlot: Boolean,
        val isOccupiedBuffTile: Boolean
    )

    private fun analyzeTileVisualEnergy(bitmap: Bitmap, rect: Rect): TileEnergyStats {
        val safe = clampRect(rect, bitmap.width, bitmap.height)
        val hsv = FloatArray(3)
        var count = 0
        var satSum = 0f
        var colorful = 0
        var lumaSum = 0.0
        var lumaSqSum = 0.0
        var darkPixels = 0

        val step = max(1, min(safe.width(), safe.height()) / 12)
        var y = safe.top
        while (y < safe.bottom) {
            var x = safe.left
            while (x < safe.right) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                lumaSum += luma
                lumaSqSum += luma * luma
                if (luma < 110.0) darkPixels++
                Color.colorToHSV(c, hsv)
                satSum += hsv[1]
                if (hsv[1] > 0.28f && hsv[2] > 0.22f) colorful++
                count++
                x += step
            }
            y += step
        }
        if (count == 0) {
            return TileEnergyStats(0f, 0f, 0f, false, false)
        }
        val meanSat = satSum / count
        val meanLuma = lumaSum / count
        val variance = (lumaSqSum / count) - (meanLuma * meanLuma)
        val stdDev = sqrt(variance.coerceAtLeast(0.0)).toFloat()
        val colorfulRatio = colorful.toFloat() / count.toFloat()
        val darkRatio = darkPixels.toFloat() / count.toFloat()

        // Empty Flyff Original View taskbar slots are uniform bright cream with a tiny gray dot
        val occupiedSlot = (colorfulRatio >= 0.16f && stdDev >= 18f) ||
            (meanSat >= 0.14f && stdDev >= 22f) ||
            (darkRatio >= 0.22f && stdDev >= 26f)

        // Active buff icons in Mobile UI have colorful artwork + dark border + bright timer digits
        val occupiedBuff = (colorfulRatio >= 0.14f && stdDev >= 24f) ||
            (meanSat >= 0.16f && stdDev >= 28f && darkRatio >= 0.12f)

        return TileEnergyStats(
            meanSat = meanSat,
            lumaStdDev = stdDev,
            colorfulPixelRatio = colorfulRatio,
            isOccupiedTaskbarSlot = occupiedSlot,
            isOccupiedBuffTile = occupiedBuff
        )
    }

    fun safeCrop(src: Bitmap, rect: Rect): Bitmap? {
        val safe = clampRect(rect, src.width, src.height)
        val w = safe.width()
        val h = safe.height()
        if (w <= 0 || h <= 0) return null
        return runCatching { Bitmap.createBitmap(src, safe.left, safe.top, w, h) }.getOrNull()
    }

    fun clampRect(rect: Rect, width: Int, height: Int): Rect {
        val l = rect.left.coerceIn(0, (width - 1).coerceAtLeast(0))
        val t = rect.top.coerceIn(0, (height - 1).coerceAtLeast(0))
        val r = rect.right.coerceIn(l + 1, width.coerceAtLeast(1))
        val b = rect.bottom.coerceIn(t + 1, height.coerceAtLeast(1))
        return Rect(l, t, r, b)
    }
}

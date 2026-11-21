package com.example.flyff

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.model.WebViewTarget
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class DetectedTaskbarSlot(
    val owner: WebViewTarget,
    val sectionIndex: Int = 0, // 0 = the 10-slot 1..0 keybar (Main / Alt / Ctrl all share this location)
    val slotIndex: Int,        // 0..9 -> Keys 1..9, 0
    val bounds: Rect,          // Tight icon bounds in full-frame pixels
    val iconBitmap: Bitmap
)

data class TaskbarScanResult(
    val owner: WebViewTarget,
    val taskbarBandRect: Rect,
    val detectedSlots: List<DetectedTaskbarSlot>,
    val sectionCounts: List<Int>,
    val previewStripRect: Rect = taskbarBandRect,
    val previewStripBitmap: Bitmap? = null
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
 * - Auto-detection of Mobile UI top-left HP/MP/FP bars and Active Buff icon grid with gap-snapped icon cropping
 * - Structural auto-detection & exact per-icon cropping of Original View (Desktop UI) 1-0 keybar slots
 *   (skipping the 20 non-keybound clickable shortcut slots to the left and utility/Action Slot to the right)
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
     * Constrains MP horizontal search to align with HP/FP so blue sky backgrounds never cause false hits,
     * and tightly bounds the 7-column Active Buff panel right below the "Buff xN" header.
     */
    fun autoDetectMobileHudBars(
        frame: Bitmap,
        paneRect: Rect,
        owner: WebViewTarget
    ): CharacterHudRegions {
        val safePane = clampRect(paneRect, frame.width, frame.height)
        val paneW = safePane.width().coerceAtLeast(100)
        val paneH = safePane.height().coerceAtLeast(100)

        // Top-left Mobile UI HP/MP/FP bars live within x: 5%..22% of pane width, y: 0.5%..16% of pane height
        val searchLeft = safePane.left + (paneW * 0.05f).toInt()
        val searchRight = safePane.left + (paneW * 0.22f).toInt()
        val searchTop = safePane.top + (paneH * 0.005f).toInt()
        val searchBottom = safePane.top + (paneH * 0.16f).toInt()

        val hpFound = findHorizontalVitalBar(frame, searchLeft, searchTop, searchRight, searchBottom, VitalType.HP)
        val fpSearchTop = hpFound?.bottom?.let { it + max(6, hpFound.height()) }
            ?: (safePane.top + (paneH * 0.045f).toInt())
        val fpFound = findHorizontalVitalBar(
            frame,
            hpFound?.let { (it.left - 10).coerceAtLeast(searchLeft) } ?: searchLeft,
            fpSearchTop,
            hpFound?.let { (it.right + 10).coerceAtMost(searchRight) } ?: searchRight,
            (fpSearchTop + (paneH * 0.09f).toInt()).coerceAtMost(searchBottom + 60),
            VitalType.FP
        )

        // Constrain MP search between HP and FP (and horizontally to HP/FP) to avoid matching blue sky
        val mpSearchLeft = listOfNotNull(hpFound?.left, fpFound?.left).minOrNull()?.let { (it - 8).coerceAtLeast(searchLeft) }
            ?: searchLeft
        val mpSearchRight = listOfNotNull(hpFound?.right, fpFound?.right).maxOrNull()?.let { (it + 8).coerceAtMost(searchRight) }
            ?: searchRight
        val mpSearchTop = hpFound?.bottom ?: (safePane.top + (paneH * 0.025f).toInt())
        val mpSearchBottom = fpFound?.top?.takeIf { it > mpSearchTop + 4 }
            ?: (mpSearchTop + (paneH * 0.06f).toInt()).coerceAtMost(searchBottom + 30)
        val mpFound = findHorizontalVitalBar(
            frame,
            mpSearchLeft,
            mpSearchTop,
            mpSearchRight,
            mpSearchBottom,
            VitalType.MP
        )

        // Harmonize horizontal left/right alignment across HP, MP, FP (they share identical width in Flyff)
        val anchorBars = listOfNotNull(hpFound, fpFound).ifEmpty { listOfNotNull(mpFound) }
        val alignedLeft = anchorBars.minOfOrNull { it.left } ?: (safePane.left + (paneW * 0.078f).toInt())
        val alignedRight = anchorBars.maxOfOrNull { it.right } ?: (safePane.left + (paneW * 0.151f).toInt())
        val barWidth = (alignedRight - alignedLeft).coerceAtLeast(40)
        val defaultBarH = listOfNotNull(hpFound, mpFound, fpFound)
            .map { it.height() }
            .average()
            .takeIf { !it.isNaN() && it >= 4.0 }
            ?.toInt()
            ?: max(8, (paneH * 0.012f).toInt())

        val hpRect = hpFound?.let { Rect(alignedLeft, it.top, alignedRight, it.bottom) }
            ?: Rect(
                alignedLeft,
                safePane.top + (paneH * 0.036f).toInt(),
                alignedRight,
                safePane.top + (paneH * 0.036f).toInt() + defaultBarH
            )
        val mpRect = mpFound?.let { Rect(alignedLeft, it.top, alignedRight, it.bottom) }
            ?: Rect(alignedLeft, hpRect.bottom + max(4, defaultBarH / 2), alignedRight, hpRect.bottom + max(4, defaultBarH / 2) + defaultBarH)
        val fpRect = fpFound?.let { Rect(alignedLeft, it.top, alignedRight, it.bottom) }
            ?: Rect(alignedLeft, mpRect.bottom + max(4, defaultBarH / 2), alignedRight, mpRect.bottom + max(4, defaultBarH / 2) + defaultBarH)

        // Tightly locate the 7-column Mobile UI Active Buff panel directly below "Buff xN"
        // In Flyff Mobile UI, the 7-icon buff grid spans x ≈ 0.8%..18.2% of paneW (alignedRight + ~0.44 * barW)
        val nominalBuffLeft = safePane.left + (paneW * 0.008f).toInt()
        val nominalBuffRight = (alignedRight + (barWidth * 0.45f).toInt())
            .coerceIn(safePane.left + (paneW * 0.165f).toInt(), safePane.left + (paneW * 0.205f).toInt())
            .coerceAtMost(safePane.right)
        val buffSearchTop = (fpRect.bottom + max(18, (paneW * 0.032f).toInt())).coerceAtMost(safePane.bottom - 50)
        val refinedBuffPanel = detectMobileBuffPanelBounds(
            frame = frame,
            safePane = safePane,
            nominalLeft = nominalBuffLeft,
            nominalRight = nominalBuffRight,
            searchTop = buffSearchTop
        )

        val estTile = ((refinedBuffPanel.width() * 0.90f) / 7f).toInt().coerceIn(20, 96)

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
            buffGridRegion = RectSpec.fromPixels(
                refinedBuffPanel.left,
                refinedBuffPanel.top,
                refinedBuffPanel.right,
                refinedBuffPanel.bottom,
                frame.width,
                frame.height
            ),
            estimatedTileWidth = estTile,
            estimatedTileHeight = estTile
        )
    }

    /**
     * Locates the exact dark-bordered 7-column active buff panel below the "Buff xN" row.
     */
    private fun detectMobileBuffPanelBounds(
        frame: Bitmap,
        safePane: Rect,
        nominalLeft: Int,
        nominalRight: Int,
        searchTop: Int
    ): Rect {
        val paneW = safePane.width().coerceAtLeast(100)
        val estPitch = ((nominalRight - nominalLeft) / 7f).coerceIn(24f, 96f)
        val maxPanelH = (estPitch * 3.4f).toInt()
        val searchBottom = (searchTop + maxPanelH + (estPitch * 1.1f).toInt()).coerceAtMost(safePane.bottom)

        // Scan rows from searchTop downward to skip the "Buff xN" header and find Row 1 start
        var buffTop = searchTop
        val spanH = (searchBottom - searchTop).coerceAtLeast(1)
        val rowSat = FloatArray(spanH)
        val rowDark = FloatArray(spanH)
        val hsv = FloatArray(3)
        for (ry in 0 until spanH) {
            val y = searchTop + ry
            var satHits = 0
            var darkHits = 0
            var total = 0
            var x = nominalLeft
            while (x < nominalRight) {
                val p = frame.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (max(r, max(g, b)) < 75) darkHits++
                Color.colorToHSV(p, hsv)
                if (hsv[1] > 0.26f && hsv[2] > 0.22f) satHits++
                total++
                x += 2
            }
            rowSat[ry] = if (total > 0) satHits.toFloat() / total else 0f
            rowDark[ry] = if (total > 0) darkHits.toFloat() / total else 0f
        }

        // Look for the FIRST dark horizontal border line right above Row 1
        // (dark >= 0.35 followed immediately by brighter/saturated icon rows)
        val headerLimit = min(spanH - 20, (estPitch * 1.15f).toInt()).coerceAtLeast(1)
        var detectedTop = -1
        for (ry in 0 until headerLimit) {
            if (rowDark[ry] >= 0.35f &&
                ry + 2 < spanH &&
                rowDark[ry + 1] < 0.22f &&
                rowDark[ry + 2] < 0.22f
            ) {
                detectedTop = searchTop + ry + 1
                break
            }
        }
        if (detectedTop >= searchTop) {
            buffTop = detectedTop
        }

        // Refine horizontal panel walls [panelLeft..panelRight] by looking for the OUTER dark vertical frame edges
        val row1End = (buffTop + estPitch.toInt()).coerceAtMost(safePane.bottom)
        val row1H = (row1End - buffTop).coerceAtLeast(10)
        var panelLeft = nominalLeft
        var panelRight = nominalRight

        val leftSearchEnd = (safePane.left + (paneW * 0.025f).toInt()).coerceAtMost(nominalRight - 40)
        for (x in safePane.left until leftSearchEnd) {
            var darkPx = 0
            for (y in buffTop until row1End) {
                val p = frame.getPixel(x, y)
                if (max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)) < 78) darkPx++
            }
            if (darkPx >= (row1H * 0.60f).toInt()) {
                panelLeft = x
            }
        }

        // Scan RIGHT-TO-LEFT so we lock onto the outermost right frame wall rather than dark pixels inside Col 6
        val rightSearchStart = (nominalRight - (estPitch * 0.35f).toInt()).coerceAtLeast(panelLeft + 60)
        val rightSearchEnd = (nominalRight + (estPitch * 0.65f).toInt()).coerceAtMost(safePane.right)
        for (x in (rightSearchEnd - 1) downTo rightSearchStart) {
            var darkPx = 0
            for (y in buffTop until row1End) {
                val p = frame.getPixel(x, y)
                if (max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)) < 78) darkPx++
            }
            if (darkPx >= (row1H * 0.62f).toInt()) {
                panelRight = x
                break
            }
        }

        val finalW = (panelRight - panelLeft).coerceAtLeast(70)
        val finalPitch = (finalW / 7f).coerceIn(22f, 96f)
        val buffBottom = (buffTop + (finalPitch * 3.15f).toInt()).coerceAtMost(safePane.bottom)
        return Rect(panelLeft, buffTop, panelRight, buffBottom)
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
     * Auto-segments the Flyff Mobile UI active buff icon grid (7 icons per row) inside `hud.buffGridRegion`.
     * Uses inter-row saturation valleys and inter-column borders to lock onto the exact square bounds of
     * every occupied buff icon (whole icon and ONLY the icon, no neighbor bleed), then splits each tile into:
     * - fullTileBitmap (exact icon + timer preview)
     * - artCropBitmap (upper ~60% clean artwork free of timer digits -> template matching)
     * - timerStripBitmap (lower ~42% -> ML Kit OCR)
     */
    fun autoDetectActiveBuffTiles(
        frame: Bitmap,
        hud: CharacterHudRegions,
        columnsPerRow: Int = 7,
        maxRows: Int = 3
    ): List<DetectedBuffTile> {
        val roi = hud.buffGridRegion.toAndroidRect(frame.width, frame.height)
        if (roi.width() < 70 || roi.height() < 30) return emptyList()

        val hsv = FloatArray(3)
        val roiH = roi.height()
        val roiW = roi.width()

        // Step 1: Compute row-wise saturation & darkness profiles to find exact Row 0 top and inter-row gap
        val rowSat = FloatArray(roiH)
        val rowDark = FloatArray(roiH)
        for (ry in 0 until roiH) {
            val y = roi.top + ry
            var satHits = 0
            var darkHits = 0
            var samples = 0
            var x = roi.left + 2
            while (x < roi.right - 2) {
                val p = frame.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (max(r, max(g, b)) < 75) darkHits++
                Color.colorToHSV(p, hsv)
                if (hsv[1] > 0.25f && hsv[2] > 0.20f) satHits++
                samples++
                x += 2
            }
            rowSat[ry] = if (samples > 0) satHits.toFloat() / samples else 0f
            rowDark[ry] = if (samples > 0) darkHits.toFloat() / samples else 0f
        }

        // Trim any outer left/right dark frame columns from the panel ROI
        var innerLeft = roi.left
        var innerRight = roi.right
        val maxTrim = (roiW / (columnsPerRow * 3)).coerceIn(2, 10)
        val probeTop = roi.top + min(4, roiH / 6)
        val probeBot = min(roi.bottom, roi.top + (roiW / columnsPerRow))
        val probeSpan = (probeBot - probeTop).coerceAtLeast(6)
        while (innerLeft < roi.left + maxTrim) {
            var dark = 0
            for (y in probeTop until probeBot) {
                val p = frame.getPixel(innerLeft, y)
                if (max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)) < 78) dark++
            }
            if (dark >= (probeSpan * 0.55f).toInt()) innerLeft++ else break
        }
        while (innerRight > roi.right - maxTrim) {
            var dark = 0
            for (y in probeTop until probeBot) {
                val p = frame.getPixel(innerRight - 1, y)
                if (max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)) < 78) dark++
            }
            if (dark >= (probeSpan * 0.55f).toInt()) innerRight-- else break
        }

        val innerW = (innerRight - innerLeft).coerceAtLeast(columnsPerRow * 16)
        val colPitch = innerW.toFloat() / columnsPerRow.toFloat()
        // In Flyff Mobile UI, each buff icon is ~87% of column pitch wide (with ~13% gap between icons)
        val nominalIconW = (colPitch * 0.875f).roundToInt().coerceIn(18, 100)

        // Find Row 0 start (skip leading dark border or blank rows)
        var startOffsetY = 0
        val peakSat = rowSat.maxOrNull() ?: 0f
        if (peakSat > 0.06f) {
            val trigger = max(0.08f, peakSat * 0.30f)
            for (ry in 0 until min(roiH - nominalIconW / 2, (colPitch * 0.8f).toInt()).coerceAtLeast(1)) {
                if (rowSat[ry] >= trigger && rowDark[ry] < 0.45f) {
                    startOffsetY = (ry - 2).coerceAtLeast(0)
                    break
                }
            }
        }

        // Refine exact rowPitch by finding the crisp low-saturation valley between Row 0 and Row 1
        var rowPitch = colPitch.roundToInt().coerceAtLeast(nominalIconW + 2)
        var tileH = (colPitch * 0.92f).roundToInt().coerceIn(18, rowPitch)
        val gapSearchMin = (startOffsetY + (colPitch * 0.80f).toInt()).coerceIn(0, roiH - 1)
        val gapSearchMax = (startOffsetY + (colPitch * 1.18f).toInt()).coerceIn(gapSearchMin, roiH - 1)
        if (gapSearchMax > gapSearchMin + 2) {
            var minSatVal = Float.MAX_VALUE
            var gapMid = -1
            for (ry in gapSearchMin..gapSearchMax) {
                if (rowSat[ry] < minSatVal) {
                    minSatVal = rowSat[ry]
                    gapMid = ry
                }
            }
            if (gapMid > startOffsetY + nominalIconW * 0.75f && minSatVal < max(0.06f, peakSat * 0.22f)) {
                // Expand gap band around gapMid where rowSat stays near-zero
                var gapTop = gapMid
                while (gapTop > gapSearchMin && rowSat[gapTop - 1] <= minSatVal + 0.03f) gapTop--
                var gapBot = gapMid
                while (gapBot < gapSearchMax && rowSat[gapBot + 1] <= minSatVal + 0.03f) gapBot++
                val measuredTileH = (gapTop - startOffsetY).coerceIn((colPitch * 0.78f).toInt(), (colPitch * 1.05f).toInt())
                val measuredPitch = (gapBot + 1 - startOffsetY).coerceIn(measuredTileH + 1, (colPitch * 1.18f).toInt())
                tileH = measuredTileH
                rowPitch = measuredPitch
            }
        }

        val tiles = mutableListOf<DetectedBuffTile>()
        val sidePad = ((colPitch - nominalIconW) / 2f).roundToInt().coerceAtLeast(1)

        for (row in 0 until maxRows) {
            val tileTop = roi.top + startOffsetY + row * rowPitch
            val tileBottom = (tileTop + tileH).coerceAtMost(roi.bottom)
            if (tileBottom - tileTop < (tileH * 0.72f).toInt()) break

            var rowFoundAny = false
            for (col in 0 until columnsPerRow) {
                val cellLeft = (innerLeft + col * colPitch).roundToInt()
                val cellRight = (innerLeft + (col + 1) * colPitch).roundToInt().coerceAtMost(innerRight)
                val nominalRect = Rect(
                    (cellLeft + sidePad).coerceIn(innerLeft, innerRight - 10),
                    tileTop,
                    (cellRight - sidePad).coerceIn(cellLeft + sidePad + 10, innerRight),
                    tileBottom
                )
                if (nominalRect.width() < 16 || nominalRect.height() < 16) continue

                val stats = analyzeTileVisualEnergy(frame, nominalRect)
                if (stats.isOccupiedBuffTile) {
                    rowFoundAny = true
                    val snappedRect = refineBuffTileBounds(frame, nominalRect, cellLeft, cellRight, tileTop, tileBottom)
                    val fullBmp = safeCrop(frame, snappedRect) ?: continue
                    val artInset = max(1, snappedRect.width() / 16)
                    val artRect = Rect(
                        snappedRect.left + artInset,
                        snappedRect.top + artInset,
                        (snappedRect.right - artInset).coerceAtLeast(snappedRect.left + artInset + 6),
                        (snappedRect.top + (snappedRect.height() * 0.60f).toInt()).coerceAtLeast(snappedRect.top + artInset + 6)
                    )
                    val timerRect = Rect(
                        snappedRect.left,
                        (snappedRect.top + (snappedRect.height() * 0.56f).toInt()).coerceAtMost(snappedRect.bottom - 8),
                        snappedRect.right,
                        snappedRect.bottom
                    )
                    val artBmp = safeCrop(frame, artRect) ?: fullBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val timerBmp = safeCrop(frame, timerRect) ?: fullBmp.copy(Bitmap.Config.ARGB_8888, false)

                    tiles.add(
                        DetectedBuffTile(
                            owner = hud.owner,
                            row = row,
                            col = col,
                            tileBounds = snappedRect,
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
     * Tightly snaps a Mobile UI active buff icon horizontally inside its column cell so any
     * outer translucent gap columns are excluded while preserving the entire square icon frame.
     */
    private fun refineBuffTileBounds(
        frame: Bitmap,
        nominal: Rect,
        cellLeft: Int,
        cellRight: Int,
        tileTop: Int,
        tileBottom: Int
    ): Rect {
        val searchL = cellLeft.coerceIn(0, frame.width - 2)
        val searchR = cellRight.coerceIn(searchL + 2, frame.width)
        val artTop = (tileTop + 2).coerceIn(0, frame.height - 2)
        val artBot = (tileTop + ((tileBottom - tileTop) * 0.68f).toInt()).coerceIn(artTop + 2, frame.height)
        val artH = (artBot - artTop).coerceAtLeast(4)
        val hsv = FloatArray(3)

        var firstActiveX = -1
        var lastActiveX = -1
        for (x in searchL until searchR) {
            var activePx = 0
            for (y in artTop until artBot) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                if (hsv[1] > 0.22f && hsv[2] > 0.20f) activePx++
            }
            if (activePx >= max(3, (artH * 0.22f).toInt())) {
                if (firstActiveX < 0) firstActiveX = x
                lastActiveX = x
            }
        }
        if (firstActiveX < 0 || lastActiveX <= firstActiveX) return nominal

        // Allow tight adjustment while keeping square proportions (avoid shrinking on monochrome icons)
        val detectedW = lastActiveX - firstActiveX + 1
        val targetW = nominal.width()
        return if (detectedW >= (targetW * 0.82f).toInt() && detectedW <= (targetW * 1.12f).toInt()) {
            Rect(
                firstActiveX.coerceIn(searchL, searchR - 10),
                tileTop,
                (lastActiveX + 1).coerceIn(firstActiveX + 10, searchR),
                tileBottom
            )
        } else {
            // Re-center nominal width around detected center if slightly shifted
            val center = (firstActiveX + lastActiveX) / 2
            val half = targetW / 2
            val l = (center - half).coerceIn(searchL, (searchR - targetW).coerceAtLeast(searchL))
            Rect(l, tileTop, (l + targetW).coerceAtMost(searchR), tileBottom)
        }
    }

    /**
     * Auto-detects Flyff Original View's 10-slot `1-0` keybar inside `paneRect` (right side of the bottom
     * taskbar, skipping the 20 non-keybound click slots on the left and utility/Action Slot on the right).
     *
     * Note: Main (`1-0`), 2nd Bar (`Alt+1-0`), and 3rd Bar (`Ctrl+1-0`) all occupy this exact 10-slot
     * location in Flyff Original View (indicated by the small bar/page number on the taskbar).
     *
     * Uses exact tray-margin detection + per-slot foreground tight-cropping so every detected slot
     * returns the WHOLE icon and ONLY the icon with zero taskbar frame or cream padding bleed.
     */
    fun autoDetectOriginalViewTaskbarSlots(
        frame: Bitmap,
        paneRect: Rect,
        owner: WebViewTarget
    ): TaskbarScanResult {
        val safePane = clampRect(paneRect, frame.width, frame.height)
        val paneW = safePane.width().coerceAtLeast(100)
        val paneH = safePane.height().coerceAtLeast(100)

        // Flyff Original View bottom taskbar scales proportionally to paneW and sits flush at pane bottom.
        // The 10-slot 1-0 keybar resides in x ≈ 61.4%..84.0% of paneW (after the 20 non-keybound click slots).
        val scanBandH = max(48, (paneW * 0.065f).toInt()).coerceAtMost((paneH * 0.20f).toInt().coerceAtLeast(48))
        val scanTop = (safePane.bottom - scanBandH).coerceAtLeast(safePane.top)
        val scanBottom = safePane.bottom

        val priorKeybarLeft = safePane.left + (paneW * 0.6145f).toInt()
        val priorKeybarRight = safePane.left + (paneW * 0.8395f).toInt()
        val priorIconSize = (paneW * 0.0204f).roundToInt().coerceIn(14, 72)

        // Step 1: Locate exact vertical icon band [iconTop..iconBottom] inside the 10-slot keybar
        val (iconTop, iconBottom) = detectTaskbarIconVerticalBand(
            frame = frame,
            safePane = safePane,
            scanTop = scanTop,
            scanBottom = scanBottom,
            sampleLeft = priorKeybarLeft,
            sampleRight = priorKeybarRight,
            priorIconSize = priorIconSize
        )
        val iconH = (iconBottom - iconTop + 1).coerceIn(12, 84)

        // Step 2: Locate exact horizontal interior of the 10-slot tray [trayLeft..trayRight]
        val (trayLeft, trayRight) = detectKeybarTrayHorizontalBounds(
            frame = frame,
            safePane = safePane,
            iconTop = iconTop,
            iconBottom = iconBottom,
            priorLeft = priorKeybarLeft,
            priorRight = priorKeybarRight,
            iconH = iconH
        )

        // Step 3: Refine the 10-slot grid pitch & origin using actual detected full-width icon runs
        val (gridStartX, slotPitch, iconW) = refineTenSlotGrid(
            frame = frame,
            trayLeft = trayLeft,
            trayRight = trayRight,
            iconTop = iconTop,
            iconBottom = iconBottom,
            iconH = iconH
        )

        val detected = mutableListOf<DetectedTaskbarSlot>()
        val halfGap = max(1, ((slotPitch - iconW) / 2f).roundToInt())

        for (slot in 0 until 10) {
            val nomLeft = (gridStartX + slot * slotPitch).roundToInt()
            val nomRight = (nomLeft + iconW).coerceAtMost(safePane.right)

            // Search window strictly bounded by midpoints of adjacent gaps so cropping can NEVER bleed into neighbors
            val cellMinX = (nomLeft - halfGap).coerceIn(safePane.left, safePane.right - 6)
            val cellMaxX = (nomRight + halfGap).coerceIn(cellMinX + 6, safePane.right)
            val nominalRect = Rect(
                nomLeft.coerceIn(safePane.left, safePane.right - 4),
                iconTop,
                nomRight.coerceIn(nomLeft + 4, safePane.right),
                (iconBottom + 1).coerceAtMost(safePane.bottom)
            )
            if (nominalRect.width() < 8 || nominalRect.height() < 8) continue

            if (!isTaskbarSlotOccupied(frame, nominalRect)) continue

            // Tight-crop to exact icon pixels inside [cellMinX..cellMaxX] x [iconTop..iconBottom+1]
            val tightRect = tightCropTaskbarIcon(
                frame = frame,
                cellLeft = cellMinX,
                cellRight = cellMaxX,
                bandTop = iconTop,
                bandBottom = (iconBottom + 1).coerceAtMost(safePane.bottom),
                nominalRect = nominalRect,
                iconH = iconH
            )
            val crop = safeCrop(frame, tightRect) ?: continue
            detected.add(
                DetectedTaskbarSlot(
                    owner = owner,
                    sectionIndex = 0,
                    slotIndex = slot,
                    bounds = tightRect,
                    iconBitmap = crop
                )
            )
        }

        val bandRect = Rect(
            trayLeft.coerceIn(safePane.left, safePane.right - 10),
            iconTop,
            trayRight.coerceIn(trayLeft + 10, safePane.right),
            (iconBottom + 1).coerceAtMost(safePane.bottom)
        )

        // Include small margin around the 10-slot keybar for the visual verification preview strip
        val padX = max(8, iconH / 2)
        val padY = max(6, iconH / 3)
        val previewRect = Rect(
            (bandRect.left - padX).coerceAtLeast(safePane.left),
            (bandRect.top - padY).coerceAtLeast(safePane.top),
            (bandRect.right + padX).coerceAtMost(safePane.right),
            (bandRect.bottom + padY).coerceAtMost(safePane.bottom)
        )
        val previewBmp = safeCrop(frame, previewRect)

        return TaskbarScanResult(
            owner = owner,
            taskbarBandRect = bandRect,
            detectedSlots = detected,
            sectionCounts = listOf(detected.size),
            previewStripRect = previewRect,
            previewStripBitmap = previewBmp
        )
    }

    /**
     * Finds the exact top & bottom pixel rows (`iconTop..iconBottom`, inclusive) of the square icons
     * inside the Original View taskbar tray, stripping both the outer brown/tan frame AND the 1–2px
     * cream inner tray margin above and below the icons.
     */
    private fun detectTaskbarIconVerticalBand(
        frame: Bitmap,
        safePane: Rect,
        scanTop: Int,
        scanBottom: Int,
        sampleLeft: Int,
        sampleRight: Int,
        priorIconSize: Int
    ): Pair<Int, Int> {
        val h = (scanBottom - scanTop).coerceAtLeast(1)
        val xL = sampleLeft.coerceIn(safePane.left, safePane.right - 2)
        val xR = sampleRight.coerceIn(xL + 2, safePane.right)

        val creamRatio = FloatArray(h)
        val nonCreamColorRatio = FloatArray(h)
        val hsv = FloatArray(3)

        for (i in 0 until h) {
            val y = scanTop + i
            var cream = 0
            var col = 0
            var total = 0
            var x = xL
            while (x < xR) {
                val p = frame.getPixel(x, y)
                if (isTaskbarCreamBg(p)) {
                    cream++
                } else {
                    Color.colorToHSV(p, hsv)
                    if (hsv[1] > 0.24f && hsv[2] > 0.18f) col++
                }
                total++
                x += 2
            }
            creamRatio[i] = if (total > 0) cream.toFloat() / total else 0f
            nonCreamColorRatio[i] = if (total > 0) col.toFloat() / total else 0f
        }

        // Find the bottom inner cream margin row (the lowest row near taskbar bottom with high cream ratio)
        var bottomMarginIdx = -1
        for (i in h - 1 downTo 0) {
            if (creamRatio[i] >= 0.48f) {
                bottomMarginIdx = i
                break
            }
        }

        if (bottomMarginIdx > 8) {
            // Find the top inner cream margin row roughly `priorIconSize` pixels above bottomMarginIdx
            val minSpan = max(10, (priorIconSize * 0.72f).toInt())
            val maxSpan = min(bottomMarginIdx, (priorIconSize * 1.45f).toInt())

            var topMarginIdx = -1
            for (i in (bottomMarginIdx - minSpan) downTo (bottomMarginIdx - maxSpan).coerceAtLeast(0)) {
                if (creamRatio[i] >= 0.45f) {
                    topMarginIdx = i
                    // Keep climbing 1-2 rows if adjacent rows are also pure cream margin (but stop at tan border)
                    while (topMarginIdx > 0 && creamRatio[topMarginIdx - 1] >= 0.45f) {
                        topMarginIdx--
                    }
                    break
                }
            }

            // If all 10 slots are packed so tight that top cream margin is slightly dimmer, find the tan border above
            if (topMarginIdx < 0) {
                for (i in (bottomMarginIdx - minSpan) downTo (bottomMarginIdx - maxSpan).coerceAtLeast(0)) {
                    if (creamRatio[i] < 0.12f && nonCreamColorRatio[i] < 0.14f) {
                        topMarginIdx = i + 1
                        break
                    }
                }
            }

            if (topMarginIdx in 0 until bottomMarginIdx - 8) {
                // Trim pure-cream inner margin rows from top downward and bottom upward
                var iconTopIdx = topMarginIdx
                while (iconTopIdx < bottomMarginIdx - 8 &&
                    creamRatio[iconTopIdx] >= 0.72f &&
                    nonCreamColorRatio[iconTopIdx] < 0.08f
                ) {
                    iconTopIdx++
                }
                var iconBotIdx = bottomMarginIdx
                while (iconBotIdx > iconTopIdx + 8 &&
                    creamRatio[iconBotIdx] >= 0.72f &&
                    nonCreamColorRatio[iconBotIdx] < 0.08f
                ) {
                    iconBotIdx--
                }
                val measuredH = iconBotIdx - iconTopIdx + 1
                if (measuredH in max(10, (priorIconSize * 0.70f).toInt())..(priorIconSize * 1.45f).toInt()) {
                    return (scanTop + iconTopIdx) to (scanTop + iconBotIdx)
                }
            }
        }

        // Clean fallback using exact proportional taskbar geometry
        val fallbackBot = (safePane.bottom - max(4, (safePane.width() * 0.0066f).toInt())).coerceAtMost(safePane.bottom - 1)
        val fallbackTop = (fallbackBot - priorIconSize + 1).coerceAtLeast(safePane.top)
        return fallbackTop to fallbackBot
    }

    /**
     * Locates the exact horizontal interior `[trayLeft..trayRight]` of the 10-slot `1-0` tray
     * by tracing the continuous cream top/bottom inner margin lines outward from the 10-slot center
     * until hitting the vertical tan/brown tray walls on the left (separator from 20 click slots)
     * and right (before utility buttons).
     */
    private fun detectKeybarTrayHorizontalBounds(
        frame: Bitmap,
        safePane: Rect,
        iconTop: Int,
        iconBottom: Int,
        priorLeft: Int,
        priorRight: Int,
        iconH: Int
    ): Pair<Int, Int> {
        val marginTopY = (iconTop - 1).coerceAtLeast(safePane.top)
        val marginBotY = (iconBottom + 1).coerceAtMost(safePane.bottom - 1)
        val centerX = ((priorLeft + priorRight) / 2).coerceIn(safePane.left, safePane.right - 1)

        fun isTrayInteriorCol(x: Int): Boolean {
            return isTaskbarCreamBg(frame.getPixel(x, marginTopY)) ||
                isTaskbarCreamBg(frame.getPixel(x, marginBotY))
        }

        // Walk left from center until we hit 2 consecutive non-cream tray-wall columns
        val minSearchX = (priorLeft - iconH * 2).coerceAtLeast(safePane.left)
        var leftEdge = centerX
        var wallHits = 0
        for (x in centerX downTo minSearchX) {
            if (isTrayInteriorCol(x)) {
                leftEdge = x
                wallHits = 0
            } else {
                wallHits++
                if (wallHits >= 2) break
            }
        }

        // Walk right from center until we hit 2 consecutive non-cream tray-wall columns
        val maxSearchX = (priorRight + iconH * 2).coerceAtMost(safePane.right - 1)
        var rightEdge = centerX
        wallHits = 0
        for (x in centerX..maxSearchX) {
            if (isTrayInteriorCol(x)) {
                rightEdge = x
                wallHits = 0
            } else {
                wallHits++
                if (wallHits >= 2) break
            }
        }

        val detectedW = rightEdge - leftEdge + 1
        val minValidW = (iconH * 9.5f).toInt()
        val maxValidW = (iconH * 12.6f).toInt()
        return if (detectedW in minValidW..maxValidW) {
            leftEdge to (rightEdge + 1)
        } else {
            priorLeft to priorRight
        }
    }

    /**
     * Refines the exact 10-slot horizontal lattice `(gridStartX, slotPitch, iconW)` inside `[trayLeft..trayRight]`
     * by measuring actual non-cream square icon runs across `[iconTop..iconBottom]`.
     */
    private fun refineTenSlotGrid(
        frame: Bitmap,
        trayLeft: Int,
        trayRight: Int,
        iconTop: Int,
        iconBottom: Int,
        iconH: Int
    ): Triple<Float, Float, Int> {
        val trayW = (trayRight - trayLeft).coerceAtLeast(40)
        val bandH = (iconBottom - iconTop + 1).coerceAtLeast(4)

        // Scan column non-cream coverage across the 10-slot tray
        val activeCol = BooleanArray(trayW)
        for (dx in 0 until trayW) {
            val x = trayLeft + dx
            var nonCream = 0
            for (y in iconTop..iconBottom) {
                if (!isTaskbarCreamBg(frame.getPixel(x, y))) nonCream++
            }
            activeCol[dx] = nonCream.toFloat() / bandH.toFloat() >= 0.30f
        }

        // Extract contiguous non-cream horizontal runs
        val runs = mutableListOf<IntRange>()
        var inRun = false
        var rs = 0
        for (dx in 0 until trayW) {
            if (activeCol[dx] && !inRun) {
                inRun = true
                rs = dx
            } else if (!activeCol[dx] && inRun) {
                inRun = false
                if (dx - rs >= max(6, (iconH * 0.45f).toInt())) {
                    runs.add((trayLeft + rs)..(trayLeft + dx - 1))
                }
            }
        }
        if (inRun && trayW - rs >= max(6, (iconH * 0.45f).toInt())) {
            runs.add((trayLeft + rs)..(trayLeft + trayW - 1))
        }

        // Filter full-width square skill icon runs (width ≈ iconH)
        val squareRuns = runs.filter { run ->
            val rw = run.last - run.first + 1
            rw in (iconH * 0.84f).toInt()..(iconH * 1.18f).toInt()
        }

        // Baseline lattice derived from tray interior width (10 icons + 9 gaps + ~2px outer padding each side)
        val baseIconW = if (squareRuns.isNotEmpty()) {
            val sortedWidths = squareRuns.map { it.last - it.first + 1 }.sorted()
            sortedWidths[sortedWidths.size / 2]
        } else {
            iconH
        }
        val basePitch = ((trayW - 2f) / 10f).coerceIn(baseIconW * 1.02f, baseIconW * 1.24f)
        val baseOrigin = trayLeft + ((trayW - (basePitch * 9f + baseIconW)) / 2f)

        if (squareRuns.isEmpty()) {
            return Triple(baseOrigin, basePitch, baseIconW)
        }

        // Refine exact pitch from adjacent or multi-slot full-width icon pairs
        val pitchSamples = mutableListOf<Float>()
        for (i in 0 until squareRuns.size) {
            for (j in i + 1 until squareRuns.size) {
                val delta = (squareRuns[j].first - squareRuns[i].first).toFloat()
                val slotStep = (delta / basePitch).roundToInt()
                if (slotStep in 1..9) {
                    val est = delta / slotStep.toFloat()
                    if (abs(est - basePitch) <= baseIconW * 0.14f) {
                        pitchSamples.add(est)
                    }
                }
            }
        }
        val refinedPitch = if (pitchSamples.isNotEmpty()) {
            val sorted = pitchSamples.sorted()
            sorted[sorted.size / 2]
        } else {
            basePitch
        }

        // Refine exact gridStartX (median of `run.first - slotIdx * refinedPitch`)
        val originSamples = squareRuns.mapNotNull { run ->
            val slotIdx = ((run.first - baseOrigin) / refinedPitch).roundToInt()
            if (slotIdx in 0..9) run.first - slotIdx * refinedPitch else null
        }
        val refinedOrigin = if (originSamples.isNotEmpty()) {
            val sorted = originSamples.sorted()
            sorted[sorted.size / 2]
        } else {
            baseOrigin
        }

        return Triple(refinedOrigin, refinedPitch, baseIconW)
    }

    /**
     * Distinguishes an occupied taskbar slot (skill icon, buff, potion, food, scroll) from an
     * empty Flyff Original View slot (uniform cream background with only a tiny low-saturation gray dot).
     */
    private fun isTaskbarSlotOccupied(frame: Bitmap, rect: Rect): Boolean {
        val safe = clampRect(rect, frame.width, frame.height)
        val hsv = FloatArray(3)
        var total = 0
        var nonCream = 0
        var colorful = 0
        var dark = 0
        var lumaSum = 0.0
        var lumaSqSum = 0.0

        val step = max(1, min(safe.width(), safe.height()) / 14)
        var y = safe.top
        while (y < safe.bottom) {
            var x = safe.left
            while (x < safe.right) {
                val c = frame.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                lumaSum += luma
                lumaSqSum += luma * luma
                if (!isTaskbarCreamBg(c)) nonCream++
                if (luma < 115.0) dark++
                Color.colorToHSV(c, hsv)
                if (hsv[1] > 0.24f && hsv[2] > 0.20f) colorful++
                total++
                x += step
            }
            y += step
        }
        if (total == 0) return false
        val nonCreamRatio = nonCream.toFloat() / total
        val colorfulRatio = colorful.toFloat() / total
        val darkRatio = dark.toFloat() / total
        val meanLuma = lumaSum / total
        val stdDev = sqrt(((lumaSqSum / total) - (meanLuma * meanLuma)).coerceAtLeast(0.0)).toFloat()

        // Empty cream + tiny gray center dot has nonCreamRatio < 0.09, colorfulRatio < 0.02, stdDev < 16
        return (nonCreamRatio >= 0.14f && colorfulRatio >= 0.07f && stdDev >= 14f) ||
            (nonCreamRatio >= 0.22f && stdDev >= 18f) ||
            (darkRatio >= 0.16f && stdDev >= 22f)
    }

    /**
     * Tightly crops an occupied taskbar slot to the exact icon pixels ("the whole icon and ONLY the icon"):
     * - Strips all outer cream gap columns and rows.
     * - Keeps 100% of full square skill icons (including their 1px dark/gold frame).
     * - Tightly encloses transparent-corner item/potion sprites + stack counts without extra cream borders.
     */
    private fun tightCropTaskbarIcon(
        frame: Bitmap,
        cellLeft: Int,
        cellRight: Int,
        bandTop: Int,
        bandBottom: Int,
        nominalRect: Rect,
        iconH: Int
    ): Rect {
        val safeL = cellLeft.coerceIn(0, frame.width - 2)
        val safeR = cellRight.coerceIn(safeL + 2, frame.width)
        val safeT = bandTop.coerceIn(0, frame.height - 2)
        val safeB = bandBottom.coerceIn(safeT + 2, frame.height)
        val h = safeB - safeT

        // Horizontal foreground profile (fraction of non-cream pixels in each column)
        val minColHits = max(2, (h * 0.12f).toInt())
        var fgL = -1
        var fgR = -1
        for (x in safeL until safeR) {
            var hits = 0
            for (y in safeT until safeB) {
                if (!isTaskbarCreamBg(frame.getPixel(x, y))) hits++
            }
            if (hits >= minColHits) {
                if (fgL < 0) fgL = x
                fgR = x
            }
        }
        if (fgL < 0 || fgR <= fgL) return nominalRect

        // Vertical foreground profile within [fgL..fgR]
        val fgW = fgR - fgL + 1
        val minRowHits = max(2, (fgW * 0.12f).toInt())
        var fgT = -1
        var fgB = -1
        for (y in safeT until safeB) {
            var hits = 0
            for (x in fgL..fgR) {
                if (!isTaskbarCreamBg(frame.getPixel(x, y))) hits++
            }
            if (hits >= minRowHits) {
                if (fgT < 0) fgT = y
                fgB = y
            }
        }
        if (fgT < 0 || fgB <= fgT) {
            fgT = safeT
            fgB = safeB - 1
        }

        // Guard against stray 1-2px noise expanding wider than a single square icon
        val maxAllowedW = (iconH * 1.12f).toInt().coerceAtLeast(nominalRect.width() + 2)
        val finalL: Int
        val finalR: Int
        if (fgW > maxAllowedW) {
            val center = (fgL + fgR) / 2
            val half = iconH / 2
            finalL = (center - half).coerceIn(safeL, safeR - iconH)
            finalR = (finalL + iconH).coerceAtMost(safeR)
        } else {
            finalL = fgL
            finalR = (fgR + 1).coerceAtMost(safeR)
        }

        return Rect(
            finalL,
            fgT,
            finalR.coerceAtLeast(finalL + 4),
            (fgB + 1).coerceAtMost(safeB).coerceAtLeast(fgT + 4)
        )
    }

    private fun isTaskbarCreamBg(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        if (maxC < 195) return false
        val sat = (maxC - minC).toFloat() / maxC.toFloat()
        return sat < 0.20f && r >= 200 && g >= 190 && b >= 175
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
            return TileEnergyStats(0f, 0f, 0f, false)
        }
        val meanSat = satSum / count
        val meanLuma = lumaSum / count
        val variance = (lumaSqSum / count) - (meanLuma * meanLuma)
        val stdDev = sqrt(variance.coerceAtLeast(0.0)).toFloat()
        val colorfulRatio = colorful.toFloat() / count.toFloat()
        val darkRatio = darkPixels.toFloat() / count.toFloat()

        // Active buff icons in Mobile UI have colorful artwork + dark border + bright timer digits
        val occupiedBuff = (colorfulRatio >= 0.14f && stdDev >= 22f) ||
            (meanSat >= 0.16f && stdDev >= 26f && darkRatio >= 0.10f)

        return TileEnergyStats(
            meanSat = meanSat,
            lumaStdDev = stdDev,
            colorfulPixelRatio = colorfulRatio,
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

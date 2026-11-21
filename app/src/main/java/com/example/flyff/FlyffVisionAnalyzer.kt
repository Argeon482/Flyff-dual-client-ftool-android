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
     * Result of a live dynamic HUD vital measurement pass (supports automatic re-anchoring
     * if the WebView or system bars shifted between calibration and runtime).
     */
    data class LiveHudVitalsMeasurement(
        val hpPct: Int?,
        val mpPct: Int?,
        val fpPct: Int?,
        val activeHud: CharacterHudRegions,
        val reanchored: Boolean = false
    )

    /**
     * Measures 0..100% fill of a calibrated HP, MP, or FP bar.
     *
     * Robust against BOTH:
     * 1. Flyff's dark-tinted depleted bar tracks (dark maroon HP, dark navy MP, dark olive FP)
     *    and 1px tinted top/bottom bevel borders — rejected via interior-row consensus,
     *    dominant-channel chroma margins, and self-calibrating relative brightness contrast.
     * 2. White numeric overlay text (e.g. "8402 / 10928") centered over the bar — evaluated
     *    using the non-text interior rows above/below the digits and right-to-left sustained fill.
     */
    fun measureBarFillPercent(frame: Bitmap, bar: BarCalibrationSpec): Int {
        val rect = bar.rect.toAndroidRect(frame.width, frame.height)
        val width = rect.width()
        val height = rect.height()
        if (width < 8 || height < 2) return 100

        // Use strictly interior core rows (skip 1px top/bottom bevel when bar height >= 5px)
        // so colored frame bevels along the depleted trough never false-match as filled!
        val yInset = when {
            height >= 9 -> 2
            height >= 5 -> 1
            else -> 0
        }
        val yStart = (rect.top + yInset).coerceIn(0, frame.height - 1)
        val yEnd = (rect.bottom - 1 - yInset).coerceIn(yStart, frame.height - 1)
        val coreRows = (yEnd - yStart + 1).coerceAtLeast(1)

        val absMinSat = max(bar.minSaturation, minFilledSatFor(bar.vitalType))
        val absMinVal = max(bar.minValue, minFilledValFor(bar.vitalType))
        val absMinDom = minDominantMarginFor(bar.vitalType)

        val hsv = FloatArray(3)
        val colStrongRatio = FloatArray(width)
        val colTextOnly = BooleanArray(width)
        val colPeakVal = FloatArray(width)
        val colPeakDom = IntArray(width)
        val colRowHues = Array(width) { FloatArray(coreRows) }
        val colRowSats = Array(width) { FloatArray(coreRows) }
        val colRowVals = Array(width) { FloatArray(coreRows) }
        val colRowDoms = Array(width) { IntArray(coreRows) }
        val colRowIsText = Array(width) { BooleanArray(coreRows) }

        val strongRefVals = ArrayList<Float>(width)
        val strongRefDoms = ArrayList<Int>(width)

        for (col in 0 until width) {
            val x = (rect.left + col).coerceIn(0, frame.width - 1)
            var nonTextRows = 0
            var strongHits = 0
            var maxVal = 0f
            var maxDom = 0

            for (ri in 0 until coreRows) {
                val y = yStart + ri
                val pixel = frame.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                Color.colorToHSV(pixel, hsv)

                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                val dom = dominantChannelMargin(bar.vitalType, r, g, b)
                colRowHues[col][ri] = h
                colRowSats[col][ri] = s
                colRowVals[col][ri] = v
                colRowDoms[col][ri] = dom

                // White/silver digit pixels (e.g. "8402 / 10928") or crisp black 1px digit outline
                val isWhiteDigit = s < 0.24f && v >= 0.56f
                val isBlackOutline = v < 0.14f && s < 0.35f
                if (isWhiteDigit || isBlackOutline) {
                    colRowIsText[col][ri] = true
                    continue
                }

                nonTextRows++
                if (matchesVitalColor(bar.vitalType, h, s, v, absMinSat, absMinVal) && dom >= absMinDom) {
                    strongHits++
                    if (v > maxVal) maxVal = v
                    if (dom > maxDom) maxDom = dom
                }
            }

            if (nonTextRows == 0) {
                colTextOnly[col] = true
                colStrongRatio[col] = 0f
            } else {
                val ratio = strongHits.toFloat() / nonTextRows.toFloat()
                colStrongRatio[col] = ratio
                colPeakVal[col] = maxVal
                colPeakDom[col] = maxDom
                if (ratio >= 0.45f) {
                    strongRefVals.add(maxVal)
                    strongRefDoms.add(maxDom)
                }
            }
        }

        // If virtually zero columns pass absolute bright filled thresholds, the bar is empty (0%)
        if (strongRefVals.size <= 1) return 0

        // Dynamic self-calibrating brightness & chroma floor derived from the bar's own filled pixels:
        // Crushes dark-tinted depleted tracks (e.g. dark maroon/navy/olive) regardless of screen gamma!
        strongRefVals.sort()
        strongRefDoms.sort()
        val refVal = strongRefVals[(strongRefVals.size * 3) / 4]
        val refDom = strongRefDoms[(strongRefDoms.size * 3) / 4]
        val dynMinVal = max(absMinVal, refVal * 0.68f)
        val dynMinDom = max(absMinDom, (refDom * 0.48f).toInt())

        val columnFilled = BooleanArray(width)
        var filledColumnCount = 0

        for (col in 0 until width) {
            if (colTextOnly[col]) continue
            var nonTextRows = 0
            var dynHits = 0
            for (ri in 0 until coreRows) {
                if (colRowIsText[col][ri]) continue
                nonTextRows++
                if (matchesVitalColor(
                        bar.vitalType,
                        colRowHues[col][ri],
                        colRowSats[col][ri],
                        colRowVals[col][ri],
                        absMinSat,
                        dynMinVal
                    ) && colRowDoms[col][ri] >= dynMinDom
                ) {
                    dynHits++
                }
            }
            val confirmed = nonTextRows > 0 && (dynHits.toFloat() / nonTextRows.toFloat()) >= 0.45f
            columnFilled[col] = confirmed
            if (confirmed) filledColumnCount++
        }

        if (filledColumnCount <= 1) return 0

        // Find rightmost sustained filled run (at least 2 of 3 consecutive columns confirmed filled)
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
            // Very low fill (1-2px at far left)
            val farLeftHits = (0 until min(6, width)).count { columnFilled[it] }
            return if (farLeftHits >= 1) ((farLeftHits * 100f) / width.toFloat()).roundToInt().coerceIn(1, 100) else 0
        }
        // Tolerate 1-2px inner dark bevel at the right tip so full bars reliably read 100%
        if (rightmostCol >= width - 3) return 100
        val pct = (((rightmostCol + 1).toFloat() / width.toFloat()) * 100f).roundToInt()
        return pct.coerceIn(0, 100)
    }

    /**
     * Dynamically measures HP, MP, and FP for a character HUD while automatically self-correcting
     * if the WebView or system insets shifted vertically/horizontally since calibration.
     */
    fun measureHudVitalsDynamic(
        frame: Bitmap,
        hud: CharacterHudRegions,
        livePaneRect: Rect? = null
    ): LiveHudVitalsMeasurement {
        val initialHp = hud.hpBar?.let { measureBarFillPercent(frame, it) }
        val initialMp = hud.mpBar?.let { measureBarFillPercent(frame, it) }
        val initialFp = hud.fpBar?.let { measureBarFillPercent(frame, it) }

        val bars = listOfNotNull(hud.hpBar, hud.mpBar, hud.fpBar)
        val anyBrightFill = (initialHp ?: 0) >= 3 || (initialMp ?: 0) >= 3 || (initialFp ?: 0) >= 3
        val looksAnchored = anyBrightFill &&
            bars.isNotEmpty() &&
            bars.all { hasPlausibleBarOrTrackVisual(frame, it) }

        if (looksAnchored) {
            return LiveHudVitalsMeasurement(
                hpPct = initialHp,
                mpPct = initialMp,
                fpPct = initialFp,
                activeHud = hud,
                reanchored = false
            )
        }

        // Dynamic re-anchor: re-locate the top-left HUD bars inside the live pane
        val effectivePane = livePaneRect?.takeIf { it.width() > 80 && it.height() > 80 }
            ?: hud.paneRect.takeIf { it.isValid }?.toAndroidRect(frame.width, frame.height)
            ?: Rect(0, 0, frame.width, frame.height)

        val fresh = autoDetectMobileHudBars(frame, effectivePane, hud.owner)
        // Preserve calibrated full bar width whenever a partially-depleted bar is detected during re-anchor!
        val calibratedWidth = bars.map { it.rect.toAndroidRect(frame.width, frame.height).width() }
            .filter { it >= 24 }
            .maxOrNull()

        fun stabilizeBar(freshBar: BarCalibrationSpec?, oldBar: BarCalibrationSpec?): BarCalibrationSpec? {
            val f = freshBar ?: return oldBar
            val fRect = f.rect.toAndroidRect(frame.width, frame.height)
            val targetW = calibratedWidth ?: fRect.width()
            // If fresh detection found a depleted bar narrower than calibrated full width, restore full right edge
            return if (targetW > fRect.width() + 4 && fRect.left + targetW <= effectivePane.right) {
                f.copy(
                    rect = RectSpec.fromPixels(
                        fRect.left,
                        fRect.top,
                        fRect.left + targetW,
                        fRect.bottom,
                        frame.width,
                        frame.height
                    )
                )
            } else {
                f
            }
        }

        val reanchoredHud = hud.copy(
            paneRect = fresh.paneRect,
            hpBar = stabilizeBar(fresh.hpBar, hud.hpBar),
            mpBar = stabilizeBar(fresh.mpBar, hud.mpBar),
            fpBar = stabilizeBar(fresh.fpBar, hud.fpBar)
        )
        val newHp = reanchoredHud.hpBar?.let { measureBarFillPercent(frame, it) }
        val newMp = reanchoredHud.mpBar?.let { measureBarFillPercent(frame, it) }
        val newFp = reanchoredHud.fpBar?.let { measureBarFillPercent(frame, it) }

        val freshHasSignal = (newHp ?: 0) >= 3 || (newMp ?: 0) >= 3 || (newFp ?: 0) >= 3
        return if (freshHasSignal) {
            LiveHudVitalsMeasurement(
                hpPct = newHp,
                mpPct = newMp,
                fpPct = newFp,
                activeHud = reanchoredHud,
                reanchored = true
            )
        } else {
            LiveHudVitalsMeasurement(
                hpPct = initialHp,
                mpPct = initialMp,
                fpPct = initialFp,
                activeHud = hud,
                reanchored = false
            )
        }
    }

    /**
     * Measures a single calibrated vital bar with automatic local vertical/horizontal re-lock
     * (used by exported `IfBarPercentStep` macros when system bars or viewports shift slightly).
     */
    fun measureSingleBarDynamic(frame: Bitmap, bar: BarCalibrationSpec): Int {
        val directPct = measureBarFillPercent(frame, bar)
        if (directPct >= 3 && hasPlausibleBarOrTrackVisual(frame, bar)) {
            return directPct
        }
        if (directPct == 0 && hasPlausibleBarOrTrackVisual(frame, bar)) {
            // Double-check local halo in case the bar shifted vertically onto dark HUD background
            val rect = bar.rect.toAndroidRect(frame.width, frame.height)
            val fullW = rect.width().coerceAtLeast(24)
            val haloL = (rect.left - 36).coerceAtLeast(0)
            val haloR = (rect.right + 36).coerceAtMost(frame.width)
            val haloT = (rect.top - 76).coerceAtLeast(0)
            val haloB = (rect.bottom + 76).coerceAtMost(frame.height)
            val relocated = findHorizontalVitalBar(frame, haloL, haloT, haloR, haloB, bar.vitalType)
            if (relocated != null && abs(relocated.centerY() - rect.centerY()) >= 6) {
                val restoredRight = max(relocated.right, relocated.left + fullW).coerceAtMost(frame.width)
                val shiftedSpec = bar.copy(
                    rect = RectSpec.fromPixels(
                        relocated.left,
                        relocated.top,
                        restoredRight,
                        relocated.bottom,
                        frame.width,
                        frame.height
                    )
                )
                val relocatedPct = measureBarFillPercent(frame, shiftedSpec)
                if (relocatedPct >= 3) return relocatedPct
            }
            return directPct
        }

        val rect = bar.rect.toAndroidRect(frame.width, frame.height)
        val fullW = rect.width().coerceAtLeast(24)
        val haloL = (rect.left - 36).coerceAtLeast(0)
        val haloR = (rect.right + 36).coerceAtMost(frame.width)
        val haloT = (rect.top - 76).coerceAtLeast(0)
        val haloB = (rect.bottom + 76).coerceAtMost(frame.height)

        val relocated = findHorizontalVitalBar(frame, haloL, haloT, haloR, haloB, bar.vitalType)
            ?: return directPct
        val restoredRight = max(relocated.right, relocated.left + fullW).coerceAtMost(frame.width)
        val shiftedSpec = bar.copy(
            rect = RectSpec.fromPixels(
                relocated.left,
                relocated.top,
                restoredRight,
                relocated.bottom,
                frame.width,
                frame.height
            )
        )
        return measureBarFillPercent(frame, shiftedSpec)
    }

    private fun hasPlausibleBarOrTrackVisual(frame: Bitmap, bar: BarCalibrationSpec): Boolean {
        val rect = bar.rect.toAndroidRect(frame.width, frame.height)
        val w = rect.width()
        val h = rect.height()
        if (w < 8 || h < 2) return false
        val yMid = ((rect.top + rect.bottom) / 2).coerceIn(0, frame.height - 1)
        val hsv = FloatArray(3)
        var vitalOrDarkCols = 0
        var whiteDigitCols = 0
        var wrongVitalCols = 0
        var total = 0
        val step = max(1, w / 28)
        var x = rect.left
        while (x < rect.right) {
            val sx = x.coerceIn(0, frame.width - 1)
            val p = frame.getPixel(sx, yMid)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            Color.colorToHSV(p, hsv)
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val isExpectedVital = matchesVitalColor(bar.vitalType, hsv[0], hsv[1], hsv[2], 0.30f, 0.35f)
            val isWrongVital = !isExpectedVital && VitalType.entries.any { other ->
                other != bar.vitalType &&
                    matchesVitalColor(other, hsv[0], hsv[1], hsv[2], 0.36f, 0.44f) &&
                    dominantChannelMargin(other, r, g, b) >= minDominantMarginFor(other)
            }
            val isWhiteDigit = hsv[1] < 0.24f && hsv[2] >= 0.56f
            val isDarkTrack = luma < 118f
            when {
                isExpectedVital -> vitalOrDarkCols++
                isWrongVital -> wrongVitalCols++
                isDarkTrack -> vitalOrDarkCols++
                isWhiteDigit -> whiteDigitCols++
            }
            total++
            x += step
        }
        if (total == 0) return false
        val vitalOrDarkRatio = vitalOrDarkCols.toFloat() / total.toFloat()
        val whiteRatio = whiteDigitCols.toFloat() / total.toFloat()
        val wrongVitalRatio = wrongVitalCols.toFloat() / total.toFloat()
        // Reject bright sky/ground (mostly white/gray), reject overlap with a different vital bar (e.g. MP rect sitting on red HP bar)
        return wrongVitalRatio < 0.22f &&
            whiteRatio <= 0.45f &&
            vitalOrDarkRatio >= 0.48f &&
            (vitalOrDarkRatio + whiteRatio) >= 0.65f
    }

    private fun dominantChannelMargin(type: VitalType, r: Int, g: Int, b: Int): Int = when (type) {
        VitalType.HP -> r - max(g, b)
        VitalType.MP -> b - r
        VitalType.FP -> g - max(r, b)
    }

    private fun minFilledSatFor(type: VitalType): Float = when (type) {
        VitalType.HP -> 0.38f
        VitalType.MP -> 0.34f
        VitalType.FP -> 0.34f
    }

    private fun minFilledValFor(type: VitalType): Float = when (type) {
        VitalType.HP -> 0.46f
        VitalType.MP -> 0.44f
        VitalType.FP -> 0.42f
    }

    private fun minDominantMarginFor(type: VitalType): Int = when (type) {
        VitalType.HP -> 40
        VitalType.MP -> 34
        VitalType.FP -> 30
    }

    fun matchesVitalColor(
        type: VitalType,
        hue: Float,
        sat: Float,
        value: Float,
        minSat: Float = 0.34f,
        minVal: Float = 0.42f
    ): Boolean {
        if (sat < minSat || value < minVal) return false
        return when (type) {
            VitalType.HP -> hue <= 26f || hue >= 332f
            VitalType.MP -> hue in 175f..262f
            VitalType.FP -> hue in 62f..168f
        }
    }

    /**
     * Auto-locates HP, MP, and FP bars + Active Buff Grid ROI inside a character's pane
     * (works for both Flyff Mobile UI and Original View top-left HUDs).
     * Uses longest-contiguous-run detection + cross-bar consensus alignment so window borders
     * never stretch bar widths (keeping full bars at 100%) and constrains buff scanning away
     * from right-hand Party member bars and lower quest tracker text.
     */
    fun autoDetectMobileHudBars(
        frame: Bitmap,
        paneRect: Rect,
        owner: WebViewTarget
    ): CharacterHudRegions {
        val safePane = clampRect(paneRect, frame.width, frame.height)
        val paneW = safePane.width().coerceAtLeast(100)
        val paneH = safePane.height().coerceAtLeast(100)

        // Top-left HP/MP/FP bars occupy a larger % of pane width on square/portrait Single screens than on wide Split-50/50 panes
        val aspect = paneW.toFloat() / paneH.toFloat()
        val searchRightFrac = when {
            aspect < 0.85f -> 0.44f
            aspect < 1.30f -> 0.36f
            else -> 0.23f
        }
        val searchLeft = safePane.left + (paneW * 0.035f).toInt()
        val searchRight = safePane.left + (paneW * searchRightFrac).toInt()
        val searchTop = safePane.top + (paneH * 0.005f).toInt()
        val searchBottom = safePane.top + (paneH * 0.16f).toInt()

        val hpFound = findHorizontalVitalBar(frame, searchLeft, searchTop, searchRight, searchBottom, VitalType.HP)
        val fpSearchTop = hpFound?.bottom?.let { it + max(6, hpFound.height()) }
            ?: (safePane.top + (paneH * 0.035f).toInt())
        val fpFound = findHorizontalVitalBar(
            frame,
            hpFound?.let { (it.left - 14).coerceAtLeast(searchLeft) } ?: searchLeft,
            fpSearchTop,
            hpFound?.let { (it.right + 14).coerceAtMost(searchRight) } ?: searchRight,
            (fpSearchTop + (paneH * 0.085f).toInt()).coerceAtMost(searchBottom + 50),
            VitalType.FP
        )

        // Constrain MP search between HP and FP (and horizontally near HP/FP) to avoid matching blue sky
        val mpSearchLeft = listOfNotNull(hpFound?.left, fpFound?.left).minOrNull()?.let { (it - 10).coerceAtLeast(searchLeft) }
            ?: searchLeft
        val mpSearchRight = listOfNotNull(hpFound?.right, fpFound?.right).maxOrNull()?.let { (it + 10).coerceAtMost(searchRight) }
            ?: searchRight
        val mpSearchTop = hpFound?.bottom ?: (safePane.top + (paneH * 0.020f).toInt())
        val mpSearchBottom = fpFound?.top?.takeIf { it > mpSearchTop + 3 }
            ?: (mpSearchTop + (paneH * 0.055f).toInt()).coerceAtMost(searchBottom + 30)
        val mpFound = findHorizontalVitalBar(
            frame,
            mpSearchLeft,
            mpSearchTop,
            mpSearchRight,
            mpSearchBottom,
            VitalType.MP
        )

        // Consensus left/right alignment across HP, MP, FP (prevents a stray red window border on HP from stretching MP/FP!)
        val detectedBars = listOfNotNull(hpFound, mpFound, fpFound)
        val alignedLeft = resolveBarEdgeConsensus(
            values = detectedBars.map { it.left },
            fallback = safePane.left + (paneW * 0.078f).toInt(),
            preferTighterInner = true
        )
        val alignedRight = resolveBarEdgeConsensus(
            values = detectedBars.map { it.right },
            fallback = safePane.left + (paneW * 0.151f).toInt(),
            preferTighterInner = false
        ).coerceAtLeast(alignedLeft + 24)

        val barWidth = (alignedRight - alignedLeft).coerceAtLeast(40)
        val defaultBarH = detectedBars
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

        // Locate the Active Buff panel directly below HP/MP/FP without bleeding into Party frames on the right.
        // Scale relative to the detected character HUD window width so both wide Split-50/50 AND square/portrait Single views match accurately.
        val nominalBuffLeft = safePane.left + (paneW * 0.006f).toInt()
        val hudWindowW = (alignedRight - nominalBuffLeft).coerceAtLeast(barWidth + 40)
        val nominalBuffRight = (nominalBuffLeft + (hudWindowW * 1.14f).toInt())
            .coerceIn(
                (alignedRight + 8).coerceAtMost(safePane.right),
                min(safePane.right, nominalBuffLeft + (hudWindowW * 1.36f).toInt())
            )
        val buffSearchTop = (fpRect.bottom + max(8, defaultBarH)).coerceAtMost(safePane.bottom - 50)
        val refinedBuffPanel = detectMobileBuffPanelBounds(
            frame = frame,
            safePane = safePane,
            nominalLeft = nominalBuffLeft,
            nominalRight = nominalBuffRight,
            searchTop = buffSearchTop
        )

        val estTile = ((refinedBuffPanel.width() * 0.90f) / 7f).toInt().coerceIn(24, 84)

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
     * Resolves a consensus horizontal edge (`left` or `right`) across HP, MP, FP bars:
     * - If 2+ bars agree within ±5px (e.g. MP & FP agree while HP touched a red frame border), uses their mean.
     * - Otherwise uses the median (or tighter inner edge for 2-bar ties) so full bars always read 100%.
     */
    private fun resolveBarEdgeConsensus(
        values: List<Int>,
        fallback: Int,
        preferTighterInner: Boolean
    ): Int {
        if (values.isEmpty()) return fallback
        if (values.size == 1) return values[0]
        val sorted = values.sorted()
        if (sorted.size >= 3) {
            val d01 = abs(sorted[1] - sorted[0])
            val d12 = abs(sorted[2] - sorted[1])
            return when {
                d01 <= 5 && d12 <= 5 -> sorted[1]
                d01 <= 5 -> (sorted[0] + sorted[1]) / 2
                d12 <= 5 -> (sorted[1] + sorted[2]) / 2
                else -> sorted[1]
            }
        }
        // 2 bars detected
        val diff = abs(sorted[1] - sorted[0])
        return if (diff <= 5) {
            (sorted[0] + sorted[1]) / 2
        } else {
            // Pick tighter inner edge (larger left, smaller right) so neither bar has dead space at its tip
            if (preferTighterInner) sorted[1] else sorted[0]
        }
    }

    private data class BuffRowBand(
        val top: Int,
        val bottom: Int,
        val timerAnchored: Boolean
    )

    private data class BuffRowLayout(
        val rows: List<BuffRowBand>,
        val tileHeight: Int,
        val rowPitch: Int
    )

    /**
     * Dynamically locates actual active buff icon rows below the HP/MP/FP window.
     *
     * Strategy 1 (Primary): Locks directly onto Flyff's bright green `MM:SS` timer bands
     * verified to have colorful icon artwork immediately above them — 100% immune to the
     * `"Buff x14 | Party x3"` tab bar, `"ms / FPS"` header text, and quest text!
     * Strategy 2 (Fallback): When buffs have no green timers, segments contiguous colorful
     * square icon bands while skipping thin header strips.
     */
    private fun detectActiveBuffRowBands(
        frame: Bitmap,
        left: Int,
        right: Int,
        searchTop: Int,
        searchBottom: Int,
        estTile: Float,
        maxRows: Int = 4
    ): BuffRowLayout? {
        val safeL = left.coerceIn(0, frame.width - 2)
        val safeR = right.coerceIn(safeL + 16, frame.width)
        val safeT = searchTop.coerceIn(0, frame.height - 2)
        val safeB = searchBottom.coerceIn(safeT + 16, frame.height)
        val spanH = safeB - safeT
        val spanW = safeR - safeL
        if (spanH < 18 || spanW < 30) return null

        val rowSat = FloatArray(spanH)
        val rowDark = FloatArray(spanH)
        val rowGreenPx = IntArray(spanH)
        val rowGreenBuckets = IntArray(spanH)
        val hsv = FloatArray(3)
        val bucketSize = max(8, (estTile * 0.45f).toInt())
        val bucketCount = (spanW / bucketSize) + 2

        for (ry in 0 until spanH) {
            val y = safeT + ry
            var satHits = 0
            var darkHits = 0
            var greenPx = 0
            var samples = 0
            val seenBuckets = BooleanArray(bucketCount)
            var x = safeL
            while (x < safeR) {
                val p = frame.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (max(r, max(g, b)) < 78) darkHits++
                // Bright green MM:SS timer digit signature (e.g. RGB ~ 65, 235, 85)
                if (g >= 125 && g - r >= 22 && g - b >= 22) {
                    greenPx++
                    val bk = ((x - safeL) / bucketSize).coerceIn(0, bucketCount - 1)
                    seenBuckets[bk] = true
                }
                Color.colorToHSV(p, hsv)
                if (hsv[1] > 0.25f && hsv[2] > 0.20f) satHits++
                samples++
                x += 2
            }
            rowSat[ry] = if (samples > 0) satHits.toFloat() / samples else 0f
            rowDark[ry] = if (samples > 0) darkHits.toFloat() / samples else 0f
            rowGreenPx[ry] = greenPx
            rowGreenBuckets[ry] = seenBuckets.count { it }
        }

        // 1. Extract contiguous vertical bands of green timer digits
        val rawGreenBands = mutableListOf<IntRange>()
        var inBand = false
        var bandStart = 0
        for (ry in 0 until spanH) {
            // Require >= 4 sampled green pixels (8px total width at stride 2) in the row
            val isGreenRow = rowGreenPx[ry] >= 4
            if (isGreenRow && !inBand) {
                inBand = true
                bandStart = ry
            } else if (!isGreenRow && inBand) {
                inBand = false
                if (ry - bandStart >= 2) {
                    rawGreenBands.add(bandStart..(ry - 1))
                }
            }
        }
        if (inBand && spanH - bandStart >= 2) {
            rawGreenBands.add(bandStart..(spanH - 1))
        }

        // Merge green sub-bands within 5px (e.g. broken digit horizontal stroke)
        val mergedGreen = mutableListOf<IntRange>()
        for (band in rawGreenBands) {
            val prev = mergedGreen.lastOrNull()
            if (prev != null && band.first - prev.last <= 5) {
                mergedGreen[mergedGreen.lastIndex] = prev.first..band.last
            } else {
                mergedGreen.add(band)
            }
        }

        // Validate each green band: REAL buff timer bands have height 3..18px AND vibrant icon art
        // directly above them (skipping the tiny green '+' icon inside the "Buff x14 | Party x3" tab bar!)
        val minArtLookback = max(10, (estTile * 0.45f).toInt())
        val maxArtLookback = max(18, (estTile * 0.78f).toInt())
        val validTimerBands = mergedGreen.filter { band ->
            val bh = band.last - band.first + 1
            if (bh < 3 || bh > max(18, (estTile * 0.42f).toInt())) return@filter false
            // Verify there is enough space above for the icon artwork
            if (band.first < max(8, (estTile * 0.35f).toInt())) return@filter false

            val peakPx = (band.first..band.last).maxOf { rowGreenPx[it] }
            val peakBuckets = (band.first..band.last).maxOf { rowGreenBuckets[it] }
            // Check artwork saturation in the band directly above the timer digits
            val artTop = (band.first - maxArtLookback).coerceAtLeast(0)
            val artBot = (band.first - 2).coerceAtLeast(artTop + 1)
            var artSatSum = 0f
            var artRows = 0
            for (ay in artTop until artBot) {
                artSatSum += rowSat[ay]
                artRows++
            }
            val meanArtSat = if (artRows > 0) artSatSum / artRows else 0f
            // Also verify colorful art directly above the actual green-pixel columns (prevents tab bar '+' hit!)
            var greenColsWithArtAbove = 0
            var totalGreenCols = 0
            val timerMidY = safeT + (band.first + band.last) / 2
            val checkArtTopY = (safeT + band.first - minArtLookback).coerceAtLeast(safeT)
            val checkArtBotY = (safeT + band.first - 3).coerceAtLeast(checkArtTopY + 1)
            var x = safeL
            while (x < safeR) {
                val tp = frame.getPixel(x, timerMidY)
                val tr = (tp shr 16) and 0xFF
                val tg = (tp shr 8) and 0xFF
                val tb = tp and 0xFF
                if (tg >= 120 && tg - tr >= 20 && tg - tb >= 20) {
                    totalGreenCols++
                    var artColHits = 0
                    var ay = checkArtTopY
                    while (ay < checkArtBotY) {
                        Color.colorToHSV(frame.getPixel(x, ay), hsv)
                        if (hsv[1] > 0.24f && hsv[2] > 0.20f) artColHits++
                        ay += 2
                    }
                    if (artColHits >= 2) greenColsWithArtAbove++
                }
                x += 2
            }
            val artAboveRatio = if (totalGreenCols > 0) greenColsWithArtAbove.toFloat() / totalGreenCols else 0f
            (peakBuckets >= 2 || peakPx >= 6) && meanArtSat >= 0.06f && artAboveRatio >= 0.35f
        }

        // Filter out consecutive timer bands that are closer than 0.65 * estTile (keep strongest)
        val distinctTimers = mutableListOf<IntRange>()
        for (band in validTimerBands) {
            val prev = distinctTimers.lastOrNull()
            if (prev != null && ((band.first + band.last) - (prev.first + prev.last)) / 2 < (estTile * 0.65f).toInt()) {
                val prevPeak = (prev.first..prev.last).maxOf { rowGreenPx[it] }
                val curPeak = (band.first..band.last).maxOf { rowGreenPx[it] }
                if (curPeak > prevPeak) {
                    distinctTimers[distinctTimers.lastIndex] = band
                }
            } else {
                distinctTimers.add(band)
            }
        }

        if (distinctTimers.isNotEmpty()) {
            // Measure exact rowPitch from consecutive timer centers if >= 2 timer rows exist
            val pitchSamples = mutableListOf<Int>()
            for (i in 0 until distinctTimers.size - 1) {
                val c0 = (distinctTimers[i].first + distinctTimers[i].last) / 2
                val c1 = (distinctTimers[i + 1].first + distinctTimers[i + 1].last) / 2
                val delta = c1 - c0
                if (delta in max(22, (estTile * 0.72f).toInt())..max(78, (estTile * 1.48f).toInt())) {
                    pitchSamples.add(delta)
                }
            }

            // Refine Row 0's exact top by walking upward from Timer 0 through the colorful icon artwork
            val t0 = distinctTimers.first()
            val expectedArtH = (estTile * 0.74f).toInt().coerceIn(16, 56)
            val artWalkMax = (t0.first - (estTile * 1.05f).toInt()).coerceAtLeast(0)
            var row0TopRel = (t0.last + 2 - estTile.roundToInt()).coerceAtLeast(0)
            // Find the lowest-saturation valley right above Row 0's artwork band
            val valleySearchBot = (t0.first - (estTile * 0.52f).toInt()).coerceIn(0, t0.first)
            val valleySearchTop = (t0.first - (estTile * 0.98f).toInt()).coerceIn(0, valleySearchBot)
            if (valleySearchBot > valleySearchTop + 2) {
                var minSat = Float.MAX_VALUE
                var bestValley = -1
                for (ry in valleySearchBot downTo valleySearchTop) {
                    if (rowSat[ry] < minSat) {
                        minSat = rowSat[ry]
                        bestValley = ry
                    }
                }
                if (bestValley >= 0 && minSat < 0.14f) {
                    // Start Row 0 right below the valley
                    var artStart = bestValley + 1
                    while (artStart < valleySearchBot && rowSat[artStart] <= minSat + 0.025f) artStart++
                    row0TopRel = (artStart - 1).coerceIn(artWalkMax, t0.first - 12)
                }
            } else {
                row0TopRel = (t0.first - expectedArtH).coerceAtLeast(0)
            }

            val measuredHFromRow0 = ((t0.last + 2) - row0TopRel).coerceIn(22, 72)
            val rowPitch: Int
            val tileH: Int
            if (pitchSamples.isNotEmpty()) {
                pitchSamples.sort()
                rowPitch = pitchSamples[pitchSamples.size / 2]
                tileH = measuredHFromRow0.coerceIn((rowPitch * 0.84f).toInt(), (rowPitch * 0.98f).toInt())
            } else {
                tileH = measuredHFromRow0
                rowPitch = (tileH * 1.07f).roundToInt().coerceAtLeast(tileH + 2)
            }

            val bands = mutableListOf<BuffRowBand>()
            for (tb in distinctTimers.take(maxRows)) {
                val rBot = (safeT + tb.last + 2).coerceAtMost(safeB)
                val rTop = (rBot - tileH).coerceAtLeast(safeT)
                if (rBot - rTop >= 18) {
                    bands.add(BuffRowBand(top = rTop, bottom = rBot, timerAnchored = true))
                }
            }

            // Also append up to 1 extra untimed row directly below the last timer row (e.g. EXP / 4 Hr / 1 Day)
            if (bands.size < maxRows) {
                val last = bands.last()
                val nextTop = last.top + rowPitch
                val nextBot = (nextTop + tileH).coerceAtMost(safeB)
                if (nextBot - nextTop >= (tileH * 0.80f).toInt()) {
                    var satSum = 0f
                    var cnt = 0
                    for (y in nextTop until nextBot) {
                        satSum += rowSat[y - safeT]
                        cnt++
                    }
                    if (cnt > 0 && (satSum / cnt) >= 0.055f) {
                        bands.add(BuffRowBand(top = nextTop, bottom = nextBot, timerAnchored = false))
                    }
                }
            }

            if (bands.isNotEmpty()) {
                return BuffRowLayout(rows = bands, tileHeight = tileH, rowPitch = rowPitch)
            }
        }

        // Strategy 2 (Fallback when buffs have no green timers):
        // Find contiguous colorful bands of height >= 0.58 * estTile (skipping thin tab headers).
        val peakSat = rowSat.maxOrNull() ?: 0f
        if (peakSat < 0.07f) return null
        val trigger = max(0.08f, peakSat * 0.30f)
        val bands = mutableListOf<BuffRowBand>()
        var ry = 0
        val minBandH = max(16, (estTile * 0.56f).toInt())
        val maxBandH = max(32, (estTile * 1.25f).toInt())
        while (ry < spanH && bands.size < maxRows) {
            if (rowSat[ry] >= trigger) {
                val bs = ry
                var lowRun = 0
                var be = ry
                while (ry < spanH) {
                    if (rowSat[ry] >= trigger * 0.65f) {
                        be = ry
                        lowRun = 0
                    } else {
                        lowRun++
                        if (lowRun >= 3) break
                    }
                    if (ry - bs >= maxBandH) break
                    ry++
                }
                val bh = be - bs + 1
                if (bh >= minBandH) {
                    val top = (safeT + bs - 1).coerceAtLeast(safeT)
                    val bot = (safeT + be + 2).coerceAtMost(safeB)
                    bands.add(BuffRowBand(top = top, bottom = bot, timerAnchored = false))
                }
            }
            ry++
        }
        if (bands.isEmpty()) return null
        val medH = bands.map { it.bottom - it.top }.sorted()[bands.size / 2].coerceIn(22, 72)
        val pitch = if (bands.size >= 2) {
            (bands[1].top - bands[0].top).coerceIn(medH + 1, (medH * 1.45f).toInt())
        } else {
            (medH * 1.07f).roundToInt()
        }
        return BuffRowLayout(rows = bands, tileHeight = medH, rowPitch = pitch)
    }

    /**
     * Locates the exact active buff panel below the HP/MP/FP window (works for both Mobile UI dark
     * buff panels WITH/WITHOUT the "Buff | Party" tab bar and Original View icon strips) while
     * strictly stopping before Party member frames.
     */
    private fun detectMobileBuffPanelBounds(
        frame: Bitmap,
        safePane: Rect,
        nominalLeft: Int,
        nominalRight: Int,
        searchTop: Int
    ): Rect {
        val paneW = safePane.width().coerceAtLeast(100)
        val estPitch = ((nominalRight - nominalLeft) / 7f).coerceIn(24f, 84f)
        // Search generously downward so we always pass below the "Buff x14 | Party x3" tab strip
        val searchBottom = (searchTop + (estPitch * 5.2f).toInt()).coerceAtMost(safePane.bottom)

        val rowLayout = detectActiveBuffRowBands(
            frame = frame,
            left = nominalLeft,
            right = min(safePane.right, nominalLeft + (estPitch * 7.4f).toInt()),
            searchTop = searchTop,
            searchBottom = searchBottom,
            estTile = estPitch,
            maxRows = 4
        )

        val buffTop: Int
        val resolvedTileH: Int
        val resolvedRowPitch: Int
        if (rowLayout != null && rowLayout.rows.isNotEmpty()) {
            buffTop = (rowLayout.rows.first().top - 2).coerceAtLeast(searchTop)
            resolvedTileH = rowLayout.tileHeight
            resolvedRowPitch = rowLayout.rowPitch
        } else {
            buffTop = searchTop
            resolvedTileH = estPitch.roundToInt().coerceIn(24, 72)
            resolvedRowPitch = (resolvedTileH * 1.07f).roundToInt()
        }

        val row1End = (buffTop + resolvedTileH).coerceAtMost(safePane.bottom)
        val row1H = (row1End - buffTop).coerceAtLeast(10)

        var panelLeft = nominalLeft
        // Find the left dark panel wall inside the leftmost 3% of the pane
        val leftSearchEnd = (safePane.left + (paneW * 0.032f).toInt()).coerceAtMost(nominalRight - 40)
        for (x in safePane.left until leftSearchEnd) {
            var darkPx = 0
            for (y in buffTop until row1End) {
                val p = frame.getPixel(x, y)
                if (max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)) < 88) darkPx++
            }
            if (darkPx >= (row1H * 0.55f).toInt()) {
                panelLeft = x
            }
        }

        // Size horizontal capacity directly from measured tile height (7 columns) so we never
        // cut off columns 5-6 AND never bleed into right-hand Party member bars!
        val colPitchEst = max(estPitch, resolvedTileH * 1.07f)
        val maxAllowedRight = min(
            safePane.right,
            panelLeft + (colPitchEst * 7.35f).toInt()
        )
        val minDesiredRight = min(
            maxAllowedRight,
            max(nominalRight, panelLeft + (colPitchEst * 6.85f).toInt())
        )
        var panelRight = minDesiredRight
        var rightmostStrongWall = -1
        for (x in (panelLeft + (colPitchEst * 5.2f).toInt()) until maxAllowedRight) {
            var darkCol = 0
            for (y in buffTop until row1End) {
                val p = frame.getPixel(x, y)
                if (max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)) < 82) darkCol++
            }
            if (darkCol >= (row1H * 0.58f).toInt()) {
                rightmostStrongWall = x
            }
        }
        if (rightmostStrongWall > panelLeft + (colPitchEst * 5.4f).toInt()) {
            panelRight = (rightmostStrongWall + 2).coerceIn(minDesiredRight, maxAllowedRight)
        }

        val rowsToCover = max(3, (rowLayout?.rows?.size ?: 0))
        val buffBottom = max(
            (rowLayout?.rows?.lastOrNull()?.bottom ?: buffTop) + 4,
            buffTop + resolvedRowPitch * rowsToCover + 4
        ).coerceAtMost(safePane.bottom)

        return Rect(panelLeft, buffTop, panelRight, buffBottom)
    }

    /**
     * Finds a horizontal HP/MP/FP vital bar inside the given search box using longest contiguous
     * colored runs per row (allowing small white numeric text gaps like "12803 / 12803", while
     * refusing to bridge across empty gaps or window borders to stray frame pixels).
     */
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
        val searchW = safeR - safeL
        val hsv = FloatArray(3)

        // Allow bridging white/black digit overlays inside the bar, never wide exterior gaps
        val maxTextGap = max(12, (searchW * 0.14f).toInt()).coerceAtMost(28)

        val rowCounts = IntArray(safeB - safeT)
        val rowSegLeft = IntArray(safeB - safeT) { -1 }
        val rowSegRight = IntArray(safeB - safeT) { -1 }

        for (y in safeT until safeB) {
            val rowIdx = y - safeT
            var bestRunHits = 0
            var bestRunStart = -1
            var bestRunEnd = -1

            var curStart = -1
            var curEnd = -1
            var curHits = 0
            var gapCount = 0

            for (x in safeL until safeR) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                val match = matchesVitalColor(vitalType, hsv[0], hsv[1], hsv[2], minSat = 0.34f, minVal = 0.28f)
                if (match) {
                    if (curStart < 0) curStart = x
                    curEnd = x
                    curHits++
                    gapCount = 0
                } else if (curStart >= 0) {
                    gapCount++
                    if (gapCount > maxTextGap) {
                        if (curHits > bestRunHits) {
                            bestRunHits = curHits
                            bestRunStart = curStart
                            bestRunEnd = curEnd
                        }
                        curStart = -1
                        curEnd = -1
                        curHits = 0
                        gapCount = 0
                    }
                }
            }
            if (curStart >= 0 && curHits > bestRunHits) {
                bestRunHits = curHits
                bestRunStart = curStart
                bestRunEnd = curEnd
            }

            rowCounts[rowIdx] = bestRunHits
            rowSegLeft[rowIdx] = bestRunStart
            rowSegRight[rowIdx] = bestRunEnd
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

        val lefts = mutableListOf<Int>()
        val rights = mutableListOf<Int>()
        for (i in bestStart..bestEnd) {
            if (rowSegLeft[i] >= 0 && rowSegRight[i] > rowSegLeft[i]) {
                lefts.add(rowSegLeft[i])
                rights.add(rowSegRight[i])
            }
        }
        if (lefts.isEmpty() || rights.isEmpty()) return null
        lefts.sort()
        rights.sort()
        val medianLeft = lefts[lefts.size / 2]
        val medianRight = rights[rights.size / 2]
        if (medianRight - medianLeft < 16) return null

        return Rect(medianLeft, safeT + bestStart, medianRight + 1, safeT + bestEnd + 1)
    }

    /**
     * Auto-segments Flyff active buff icons inside `hud.buffGridRegion` using green-timer-anchored
     * row detection + multi-row square icon lattice measurement.
     *
     * - Automatically skips the `"Buff x14 | Party x3"` tab strip even if it sits inside the ROI.
     * - Measures exact per-row Y bounds so Row 0, Row 1, Row 2, and Row 3 never drift vertically.
     * - Crops every active buff icon as a **strictly square full icon** (never side-clipping narrow
     *   sprites like Quick Step or Mental Sign) while rejecting Party member bars and quest text.
     */
    fun autoDetectActiveBuffTiles(
        frame: Bitmap,
        hud: CharacterHudRegions,
        columnsPerRow: Int = 7,
        maxRows: Int = 4
    ): List<DetectedBuffTile> {
        val rawRoi = hud.buffGridRegion.toAndroidRect(frame.width, frame.height)
        if (rawRoi.width() < 60 || rawRoi.height() < 24) return emptyList()

        val paneRect = hud.paneRect.takeIf { it.isValid }?.toAndroidRect(frame.width, frame.height)
            ?: Rect(0, 0, frame.width, frame.height)

        // Trim any outer left/right dark frame columns from the panel ROI
        val roiW = rawRoi.width()
        var innerLeft = rawRoi.left
        var innerRight = rawRoi.right
        val maxTrim = (roiW / (columnsPerRow * 3)).coerceIn(2, 10)
        val probeTop = rawRoi.top + min(4, rawRoi.height() / 6)
        val probeBot = min(rawRoi.bottom, rawRoi.top + (roiW / columnsPerRow).coerceAtLeast(24))
        val probeSpan = (probeBot - probeTop).coerceAtLeast(6)
        while (innerLeft < rawRoi.left + maxTrim) {
            var dark = 0
            for (y in probeTop until probeBot) {
                val p = frame.getPixel(innerLeft, y)
                if (max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)) < 78) dark++
            }
            if (dark >= (probeSpan * 0.55f).toInt()) innerLeft++ else break
        }
        while (innerRight > rawRoi.right - maxTrim) {
            var dark = 0
            for (y in probeTop until probeBot) {
                val p = frame.getPixel(innerRight - 1, y)
                if (max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)) < 78) dark++
            }
            if (dark >= (probeSpan * 0.55f).toInt()) innerRight-- else break
        }

        val innerW = (innerRight - innerLeft).coerceAtLeast(columnsPerRow * 14)
        val priorPitch = (innerW.toFloat() / columnsPerRow.toFloat()).coerceIn(24f, 72f)

        // Allow scanning slightly below rawRoi.bottom inside the pane in case a previously saved
        // calibration ended slightly above Row 1/Row 2
        val dynamicSearchBottom = min(
            paneRect.bottom,
            max(rawRoi.bottom, rawRoi.top + (priorPitch * 4.4f).toInt())
        )
        val rowLayout = detectActiveBuffRowBands(
            frame = frame,
            left = innerLeft,
            right = innerRight,
            searchTop = rawRoi.top,
            searchBottom = dynamicSearchBottom,
            estTile = priorPitch,
            maxRows = maxRows
        )

        val rowBands: List<BuffRowBand>
        val tileH: Int
        if (rowLayout != null && rowLayout.rows.isNotEmpty()) {
            rowBands = rowLayout.rows
            tileH = rowLayout.tileHeight
        } else {
            val fallbackTileH = (priorPitch * 0.92f).roundToInt().coerceIn(22, 68)
            val fallbackPitch = priorPitch.roundToInt().coerceAtLeast(fallbackTileH + 2)
            rowBands = (0 until maxRows).mapNotNull { r ->
                val t = rawRoi.top + r * fallbackPitch
                val b = (t + fallbackTileH).coerceAtMost(rawRoi.bottom)
                if (b - t >= (fallbackTileH * 0.72f).toInt()) BuffRowBand(t, b, false) else null
            }
            tileH = fallbackTileH
        }
        if (rowBands.isEmpty()) return emptyList()

        // Measure TRUE horizontal icon lattice across ALL detected rows for sub-pixel grid lock!
        val (gridStartX, colPitch, measuredIconW) = measureBuffColumnLattice(
            frame = frame,
            innerLeft = innerLeft,
            innerRight = innerRight,
            rowBands = rowBands,
            tileH = tileH,
            priorPitch = priorPitch,
            maxCols = columnsPerRow
        )

        // Strictly square icon width locked within ±2px of tileH so narrow sprites (e.g. Quick Step)
        // are NEVER side-clipped into skinny vertical strips!
        val squareIconW = measuredIconW.coerceIn((tileH - 2).coerceAtLeast(18), tileH + 2)
        val actualCols = (((innerRight - gridStartX + 4f) / colPitch).toInt()).coerceIn(1, columnsPerRow)
        val tiles = mutableListOf<DetectedBuffTile>()

        for ((rowIdx, band) in rowBands.withIndex()) {
            val tileTop = band.top
            val tileBottom = band.bottom
            if (tileBottom - tileTop < 16) continue

            var rowFoundAny = false
            var consecutiveMisses = 0
            for (col in 0 until actualCols) {
                val nomLeft = (gridStartX + col * colPitch).roundToInt()
                val nomRight = (nomLeft + squareIconW).coerceAtMost(frame.width)
                val halfGap = max(2, ((colPitch - squareIconW) / 2f).roundToInt())
                val cellLeft = (nomLeft - halfGap).coerceIn(innerLeft, (innerRight - 10).coerceAtLeast(innerLeft))
                val cellRight = (nomRight + halfGap).coerceIn(cellLeft + 10, min(frame.width, innerRight + 4))
                val nominalRect = Rect(
                    nomLeft.coerceIn(innerLeft, (frame.width - 10).coerceAtLeast(innerLeft)),
                    tileTop,
                    nomRight.coerceIn(nomLeft + 10, frame.width),
                    tileBottom
                )
                if (nominalRect.width() < 16 || nominalRect.height() < 16) continue

                val stats = analyzeTileVisualEnergy(frame, nominalRect, timerRowHint = band.timerAnchored)
                if (stats.isOccupiedBuffTile) {
                    rowFoundAny = true
                    consecutiveMisses = 0
                    val snappedRect = refineBuffTileBounds(
                        frame = frame,
                        nominal = nominalRect,
                        cellLeft = cellLeft,
                        cellRight = cellRight,
                        tileTop = tileTop,
                        tileBottom = tileBottom
                    )
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
                            row = rowIdx,
                            col = col,
                            tileBounds = snappedRect,
                            fullTileBitmap = fullBmp,
                            artCropBitmap = artBmp,
                            timerStripBitmap = timerBmp
                        )
                    )
                } else {
                    consecutiveMisses++
                    if (consecutiveMisses >= 2 && col >= 2) break
                }
            }
            if (!rowFoundAny && rowIdx > 0) break
        }
        return tiles
    }

    /**
     * Measures exact `(gridStartX, colPitch, iconW)` across ALL detected buff rows simultaneously
     * so the column grid locks to square icon frames with sub-pixel accuracy even if one icon in
     * Row 0 has dark/narrow artwork.
     */
    private fun measureBuffColumnLattice(
        frame: Bitmap,
        innerLeft: Int,
        innerRight: Int,
        rowBands: List<BuffRowBand>,
        tileH: Int,
        priorPitch: Float,
        maxCols: Int
    ): Triple<Float, Float, Int> {
        val innerW = (innerRight - innerLeft).coerceAtLeast(40)
        val hsv = FloatArray(3)
        val colEnergy = FloatArray(innerW)

        // Aggregate upper-art saturation energy across the first 2 detected rows (taking max per col
        // so an icon in Row 1 fills any dark edge of a narrow icon in Row 0)
        val bandsToUse = rowBands.take(2)
        for (band in bandsToUse) {
            val bandH = (band.bottom - band.top).coerceAtLeast(8)
            val artTop = (band.top + 2).coerceIn(0, frame.height - 2)
            val artBot = (band.top + (bandH * 0.68f).toInt()).coerceIn(artTop + 4, frame.height)
            val artH = (artBot - artTop).coerceAtLeast(4)
            for (dx in 0 until innerW) {
                val x = innerLeft + dx
                var active = 0
                for (y in artTop until artBot) {
                    Color.colorToHSV(frame.getPixel(x, y), hsv)
                    if (hsv[1] > 0.20f && hsv[2] > 0.18f) active++
                }
                val e = active.toFloat() / artH.toFloat()
                if (e > colEnergy[dx]) colEnergy[dx] = e
            }
        }

        // Extract contiguous active icon runs (width expected near tileH)
        val minRunW = max(14, (tileH * 0.62f).toInt())
        val maxRunW = (tileH * 1.18f).toInt()
        val runs = mutableListOf<IntRange>()
        var inRun = false
        var rs = 0
        for (dx in 0 until innerW) {
            val isOn = colEnergy[dx] >= 0.18f
            if (isOn && !inRun) {
                inRun = true
                rs = dx
            } else if (!isOn && inRun) {
                inRun = false
                val rw = dx - rs
                if (rw in minRunW..maxRunW) {
                    runs.add((innerLeft + rs)..(innerLeft + dx - 1))
                }
            }
        }
        if (inRun) {
            val rw = innerW - rs
            if (rw in minRunW..maxRunW) {
                runs.add((innerLeft + rs)..(innerLeft + innerW - 1))
            }
        }

        val fallbackIconW = tileH.coerceIn(22, 66)
        val fallbackPitch = (tileH * 1.07f).coerceIn(fallbackIconW + 2f, max(priorPitch, fallbackIconW * 1.24f))
        val fallbackStart = innerLeft + max(1f, (fallbackPitch - fallbackIconW) / 2f)

        if (runs.size < 2) {
            val clampedPitch = priorPitch.coerceIn(fallbackIconW * 1.02f, fallbackIconW * 1.22f)
            val startX = runs.firstOrNull()?.let { r ->
                val center = (r.first + r.last) / 2f
                (center - fallbackIconW / 2f).coerceAtLeast(innerLeft.toFloat())
            } ?: (innerLeft + max(1f, (clampedPitch - fallbackIconW) / 2f))
            return Triple(startX, clampedPitch, fallbackIconW)
        }

        val sortedW = runs.map { it.last - it.first + 1 }.sorted()
        val iconW = sortedW[sortedW.size / 2].coerceIn((tileH - 2).coerceAtLeast(18), tileH + 2)

        // Measure inter-icon pitch directly from consecutive run centers (invariant to narrow sprites!)
        val centers = runs.map { (it.first + it.last) / 2f }
        val pitchSamples = mutableListOf<Float>()
        for (i in 0 until centers.size - 1) {
            val delta = centers[i + 1] - centers[i]
            if (delta in (tileH * 0.98f)..(tileH * 1.32f)) {
                pitchSamples.add(delta)
            }
        }
        val refinedPitch = if (pitchSamples.isNotEmpty()) {
            val sp = pitchSamples.sorted()
            sp[sp.size / 2]
        } else {
            fallbackPitch
        }

        // Compute origin X from run centers minus half square icon width
        val originSamples = centers.mapNotNull { center ->
            val colIdx = ((center - centers.first()) / refinedPitch).roundToInt()
            if (colIdx in 0 until maxCols) (center - iconW / 2f) - colIdx * refinedPitch else null
        }
        val refinedStart = if (originSamples.isNotEmpty()) {
            val so = originSamples.sorted()
            so[so.size / 2].coerceAtLeast(innerLeft.toFloat())
        } else {
            fallbackStart
        }

        return Triple(refinedStart, refinedPitch, iconW)
    }

    /**
     * Centers a square active buff tile cleanly inside its gap-bounded column cell while ALWAYS
     * preserving full square icon width (never clipping narrow sprites like Quick Step or Mental Sign).
     */
    private fun refineBuffTileBounds(
        frame: Bitmap,
        nominal: Rect,
        cellLeft: Int,
        cellRight: Int,
        tileTop: Int,
        tileBottom: Int
    ): Rect {
        val targetW = nominal.width().coerceAtLeast(16)
        val searchL = cellLeft.coerceIn(0, (frame.width - targetW).coerceAtLeast(0))
        val searchR = cellRight.coerceIn(searchL + targetW, frame.width)
        val artTop = (tileTop + 2).coerceIn(0, frame.height - 2)
        val artBot = (tileTop + ((tileBottom - tileTop) * 0.65f).toInt()).coerceIn(artTop + 2, frame.height)
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
            if (activePx >= max(2, (artH * 0.20f).toInt())) {
                if (firstActiveX < 0) firstActiveX = x
                lastActiveX = x
            }
        }
        if (firstActiveX < 0 || lastActiveX <= firstActiveX) return nominal

        // Only allow a subtle ±3px horizontal center nudge while ALWAYS keeping full square width `targetW`
        val nominalCenter = (nominal.left + nominal.right) / 2
        val artCenter = (firstActiveX + lastActiveX) / 2
        val nudgedCenter = (artCenter.coerceIn(nominalCenter - 3, nominalCenter + 3))
        val l = (nudgedCenter - targetW / 2).coerceIn(searchL, (searchR - targetW).coerceAtLeast(searchL))
        return Rect(l, tileTop, (l + targetW).coerceAtMost(frame.width), tileBottom)
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
                // (use >= 93% cream threshold so even a 1-icon bar keeps its full square top/bottom borders)
                var iconTopIdx = topMarginIdx
                while (iconTopIdx < bottomMarginIdx - 8 &&
                    creamRatio[iconTopIdx] >= 0.93f &&
                    nonCreamColorRatio[iconTopIdx] < 0.03f
                ) {
                    iconTopIdx++
                }
                var iconBotIdx = bottomMarginIdx
                while (iconBotIdx > iconTopIdx + 8 &&
                    creamRatio[iconBotIdx] >= 0.93f &&
                    nonCreamColorRatio[iconBotIdx] < 0.03f
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

    private fun analyzeTileVisualEnergy(
        bitmap: Bitmap,
        rect: Rect,
        timerRowHint: Boolean = false
    ): TileEnergyStats {
        val safe = clampRect(rect, bitmap.width, bitmap.height)
        val hsv = FloatArray(3)
        var count = 0
        var satSum = 0f
        var colorful = 0
        var lumaSum = 0.0
        var lumaSqSum = 0.0
        var darkPixels = 0

        // Split tile vertically into Upper art (8..44%), Mid art (36..68%), and Lower half (50..94%)
        // so we can reject Party member bars (thin red HP stripe at top + flat pale-gray card below)
        // and snowy world terrain / quest text.
        val tileH = safe.height().coerceAtLeast(1)
        val upperEndY = safe.top + (tileH * 0.44f).toInt()
        val midStartY = safe.top + (tileH * 0.36f).toInt()
        val midEndY = safe.top + (tileH * 0.68f).toInt()
        val lowerStartY = safe.top + (tileH * 0.50f).toInt()

        var upperCount = 0
        var upperColorful = 0
        var midCount = 0
        var midColorful = 0
        var lowerCount = 0
        var lowerPaleFlat = 0
        var lowerGreenTimer = 0

        val step = max(1, min(safe.width(), safe.height()) / 14)
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
                val isColorful = hsv[1] > 0.26f && hsv[2] > 0.20f
                if (isColorful) colorful++

                if (y < upperEndY) {
                    upperCount++
                    if (hsv[1] > 0.22f && hsv[2] > 0.18f) upperColorful++
                }
                if (y in midStartY until midEndY) {
                    midCount++
                    if (hsv[1] > 0.22f && hsv[2] > 0.18f) midColorful++
                }
                if (y >= lowerStartY) {
                    lowerCount++
                    if (luma > 160.0 && hsv[1] < 0.16f) lowerPaleFlat++
                    if (g >= 125 && g - r >= 22 && g - b >= 22) lowerGreenTimer++
                }

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
        val upperColorfulRatio = if (upperCount > 0) upperColorful.toFloat() / upperCount else 0f
        val midColorfulRatio = if (midCount > 0) midColorful.toFloat() / midCount else 0f
        val lowerPaleRatio = if (lowerCount > 0) lowerPaleFlat.toFloat() / lowerCount else 0f
        val hasGreenTimer = lowerCount > 0 && (lowerGreenTimer.toFloat() / lowerCount) >= 0.025f

        // Reject Party member bars & snow/text tiles: flat bright/gray lower half without green timer
        // or red stripe only in top band with almost zero mid-band artwork saturation.
        val isFlatPartyOrSnowCard = (lowerPaleRatio >= 0.52f && !hasGreenTimer) ||
            (upperColorfulRatio >= 0.14f && midColorfulRatio < 0.05f && !hasGreenTimer && darkRatio < 0.18f)

        // Active buff icons in Mobile UI / Original View have colorful artwork + dark border + timer digits
        val hasTwoBandArt = (upperColorfulRatio >= 0.09f && midColorfulRatio >= 0.07f) ||
            (colorfulRatio >= 0.20f && darkRatio >= 0.12f) ||
            (hasGreenTimer && (upperColorfulRatio >= 0.06f || midColorfulRatio >= 0.06f || colorfulRatio >= 0.08f))

        val occupiedBuff = !isFlatPartyOrSnowCard && hasTwoBandArt && (
            (hasGreenTimer && stdDev >= 18f) ||
                (timerRowHint && colorfulRatio >= 0.12f && stdDev >= 20f && darkRatio >= 0.10f) ||
                (colorfulRatio >= 0.14f && stdDev >= 22f) ||
                (meanSat >= 0.16f && stdDev >= 26f && darkRatio >= 0.10f)
            )

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

package com.example.flyff.ui

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.data.MacroRepository
import com.example.dualweb.DualLayoutMode
import com.example.flyff.ActiveBuffDefinition
import com.example.flyff.BarCalibrationSpec
import com.example.flyff.BuffKeyLink
import com.example.flyff.BuffTimerOcrReader
import com.example.flyff.CharacterHudRegions
import com.example.flyff.CombatRotationConfig
import com.example.flyff.CooldownSkillRule
import com.example.flyff.FlyffBuiltinCatalog
import com.example.flyff.FlyffIconMatcher
import com.example.flyff.FlyffMacroCompiler
import com.example.flyff.FlyffProfile
import com.example.flyff.FlyffSkillCatalogManager
import com.example.flyff.FlyffVisionAnalyzer
import com.example.flyff.HealRuleConfig
import com.example.flyff.LayoutHudCalibration
import com.example.flyff.RectSpec
import com.example.flyff.ScannedSkillKey
import com.example.flyff.SkillCategory
import com.example.flyff.SmartFlyffEngine
import com.example.flyff.TaskbarGroupKey
import com.example.flyff.VitalTriggerRule
import com.example.flyff.VitalType
import com.example.model.MacroDefinition
import com.example.model.WebViewTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class HudNudgeTarget(val label: String) {
    HP("HP"),
    MP("MP"),
    FP("FP"),
    BUFF_GRID("Buff Grid")
}

data class PendingSlotDraft(
    val tempId: String = UUID.randomUUID().toString(),
    val owner: WebViewTarget,
    val sectionIndex: Int,
    val slotIndex: Int,
    val barPage: Int?,
    val groupKey: TaskbarGroupKey,
    val label: String,
    val category: SkillCategory,
    val include: Boolean = true,
    val iconBitmap: Bitmap,
    val autoMatchConfidencePct: Int? = null,
    val autoMatchSource: String? = null
) {
    fun keyBindingPreview(): String {
        val (keyCode, keyLabel) = ScannedSkillKey.keyCodeForSlotIndex(slotIndex)
        return ScannedSkillKey(
            owner = owner,
            keyCode = keyCode,
            keyLabel = keyLabel,
            metaState = groupKey.metaState,
            barPage = barPage,
            groupKey = groupKey,
            slotIndex = slotIndex
        ).formattedKeyBinding()
    }
}

data class PendingBuffDraft(
    val tempId: String = UUID.randomUUID().toString(),
    val owner: WebViewTarget,
    val row: Int,
    val col: Int,
    val label: String,
    val detectedTimerSec: Int?,
    val detectedTimerRaw: String,
    val include: Boolean = true,
    val fullTileBitmap: Bitmap,
    val artCropBitmap: Bitmap,
    val autoMatchConfidencePct: Int? = null,
    val autoMatchSource: String? = null,
    val matchedSkillKeyId: String? = null
)

data class LiveVitalReadout(
    val hpPct: Int? = null,
    val mpPct: Int? = null,
    val fpPct: Int? = null
)

data class TaskbarPreviewCapture(
    val owner: WebViewTarget,
    val stripBitmap: Bitmap,
    val stripRect: Rect,
    val slotRects: List<Pair<Int, Rect>> // slotIndex (0..9) to full-frame tight Rect
)

data class FlyffSetupUiState(
    val stepIndex: Int = 0, // 0=Profile, 1=Keys, 2=HUD & Buffs, 3=Link Buffs, 4=Rules
    val profile: FlyffProfile = FlyffProfile(),
    val activeScanPage: Int? = null, // null = current bar, or 1..9 for F1..F9
    val activeKeyBarGroup: TaskbarGroupKey = TaskbarGroupKey.MAIN_1_0,
    val taskbarPreviews: List<TaskbarPreviewCapture> = emptyList(),
    val pendingSlots: List<PendingSlotDraft> = emptyList(),
    val selectedHudLayout: DualLayoutMode = DualLayoutMode.SPLIT_50_50,
    val hudPreviewBitmap: Bitmap? = null,
    val hudPreviewLayout: DualLayoutMode? = null,
    val liveVitalReadouts: Map<WebViewTarget, LiveVitalReadout> = emptyMap(),
    val pendingBuffs: List<PendingBuffDraft> = emptyList(),
    val stepScrollPositions: Map<Int, Int> = emptyMap(),
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val statusIsError: Boolean = false
)

/**
 * Shared stateful controller powering the 5-step Guided Flyff Auto-Setup Wizard
 * both inside DualWebActivity (overlay) and in MainActivity (Studio screen).
 */
class FlyffSetupController(
    private val repository: MacroRepository,
    private val timerOcrReader: BuffTimerOcrReader,
    private val smartFlyffEngine: SmartFlyffEngine,
    private val scope: CoroutineScope,
    initialProfile: FlyffProfile? = null,
    initialStep: Int = 0,
    initialLayout: DualLayoutMode = DualLayoutMode.SPLIT_50_50,
    private val catalogManager: FlyffSkillCatalogManager? = null
) {
    private var hasUserEditedProfile: Boolean = initialProfile != null

    private val _uiState = MutableStateFlow(
        FlyffSetupUiState(
            stepIndex = initialStep.coerceIn(0, 4),
            profile = initialProfile ?: FlyffProfile(),
            selectedHudLayout = initialLayout
        )
    )
    val uiState: StateFlow<FlyffSetupUiState> = _uiState.asStateFlow()

    /**
     * Hydrates the wizard when a saved profile finishes loading asynchronously from Room
     * (e.g. navigating into AppRoute.FlyffSetup before the flow emits its first list).
     */
    fun hydrateProfileIfUnedited(savedProfile: FlyffProfile?) {
        if (savedProfile == null || hasUserEditedProfile) return
        hasUserEditedProfile = true
        _uiState.update { state ->
            state.copy(
                profile = savedProfile,
                selectedHudLayout = savedProfile.layouts.keys.firstOrNull() ?: state.selectedHudLayout
            )
        }
    }

    fun setStep(index: Int) {
        _uiState.update { it.copy(stepIndex = index.coerceIn(0, 4), statusMessage = null) }
    }

    fun updateStepScroll(stepIndex: Int, scrollOffset: Int) {
        _uiState.update { state ->
            val current = state.stepScrollPositions[stepIndex]
            if (current == scrollOffset) state
            else state.copy(stepScrollPositions = state.stepScrollPositions + (stepIndex to scrollOffset.coerceAtLeast(0)))
        }
    }

    fun clearStatus() {
        _uiState.update { it.copy(statusMessage = null, statusIsError = false) }
    }

    fun updateProfile(transform: (FlyffProfile) -> FlyffProfile) {
        hasUserEditedProfile = true
        _uiState.update { it.copy(profile = transform(it.profile).copy(updatedAt = System.currentTimeMillis())) }
    }

    fun setActiveScanPage(page: Int?) {
        _uiState.update { state ->
            state.copy(
                activeScanPage = page,
                pendingSlots = state.pendingSlots.map { it.copy(barPage = page) }
            )
        }
    }

    fun setActiveKeyBarGroup(groupKey: TaskbarGroupKey) {
        _uiState.update { state ->
            state.copy(
                activeKeyBarGroup = groupKey,
                pendingSlots = state.pendingSlots.map { slot ->
                    slot.copy(groupKey = groupKey)
                }
            )
        }
    }

    fun setSelectedHudLayout(layout: DualLayoutMode) {
        _uiState.update { it.copy(selectedHudLayout = layout) }
    }

    /**
     * Runs Original View 10-slot 1-0 keybar auto-detection on a captured frame, auto-sorting
     * slots into Account 1 and/or Account 2 based on DualWebActivity's live pane rects.
     */
    fun processOriginalViewCapture(
        frame: Bitmap,
        layoutMode: DualLayoutMode,
        acct1Rect: Rect,
        acct2Rect: Rect,
        ownerOverride: WebViewTarget? = null
    ) {
        scope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "Scanning 1–0 keybar slots...", statusIsError = false) }
            val currentGroup = _uiState.value.activeKeyBarGroup
            val currentPage = _uiState.value.activeScanPage
            val catalogEntries = catalogManager?.state?.value?.entries ?: FlyffBuiltinCatalog.entries

            val (scannedDrafts, previews) = withContext(Dispatchers.Default) {
                val panesToScan = resolveVisiblePanes(frame, layoutMode, acct1Rect, acct2Rect, ownerOverride)
                val out = mutableListOf<PendingSlotDraft>()
                val previewList = mutableListOf<TaskbarPreviewCapture>()
                for ((owner, paneRect) in panesToScan) {
                    val scan = FlyffVisionAnalyzer.autoDetectOriginalViewTaskbarSlots(frame, paneRect, owner)
                    scan.previewStripBitmap?.let { stripBmp ->
                        previewList.add(
                            TaskbarPreviewCapture(
                                owner = owner,
                                stripBitmap = stripBmp,
                                stripRect = scan.previewStripRect,
                                slotRects = scan.detectedSlots.map { it.slotIndex to it.bounds }
                            )
                        )
                    }
                    scan.detectedSlots.forEach { slot ->
                        val (_, keyNum) = ScannedSkillKey.keyCodeForSlotIndex(slot.slotIndex)
                        val autoMatch = FlyffIconMatcher.identifyTaskbarSkillIcon(
                            crop = slot.iconBitmap,
                            catalogEntries = catalogEntries,
                            owner = owner
                        )
                        val defaultCat = autoMatch?.recommendedCategory
                            ?: suggestDefaultCategory(owner, currentGroup, slot.slotIndex)
                        val defaultLabel = autoMatch?.label ?: if (
                            owner == WebViewTarget.ACCOUNT_2 &&
                            currentGroup == TaskbarGroupKey.MAIN_1_0 &&
                            slot.slotIndex == 0
                        ) {
                            "Heal"
                        } else {
                            "${owner.shortLabel} Key $keyNum"
                        }
                        out.add(
                            PendingSlotDraft(
                                owner = owner,
                                sectionIndex = 0,
                                slotIndex = slot.slotIndex,
                                barPage = currentPage,
                                groupKey = currentGroup,
                                label = defaultLabel,
                                category = defaultCat,
                                include = true,
                                iconBitmap = slot.iconBitmap,
                                autoMatchConfidencePct = autoMatch?.confidencePct,
                                autoMatchSource = autoMatch?.sourceTag
                            )
                        )
                    }
                }
                out to previewList
            }

            if (!frame.isRecycled) frame.recycle()

            val oldPending = _uiState.value.pendingSlots
            val oldPreviews = _uiState.value.taskbarPreviews
            val autoMatchedCount = scannedDrafts.count { it.autoMatchConfidencePct != null }
            _uiState.update {
                it.copy(
                    pendingSlots = scannedDrafts,
                    taskbarPreviews = previews,
                    isBusy = false,
                    statusMessage = if (scannedDrafts.isEmpty()) {
                        "No occupied 1–0 key slots found. Make sure Flyff is in Original View with skills in the 1–0 bar."
                    } else if (autoMatchedCount > 0) {
                        "Cropped ${scannedDrafts.size} slot(s) from ${currentGroup.shortLabel} ($autoMatchedCount auto-identified). Tap Save Slots or Save."
                    } else {
                        "Auto-cropped ${scannedDrafts.size} icon(s) from the ${currentGroup.shortLabel} bar. Label each below and tap Save Slots."
                    },
                    statusIsError = scannedDrafts.isEmpty()
                )
            }
            oldPending.forEach { if (!it.iconBitmap.isRecycled) it.iconBitmap.recycle() }
            oldPreviews.forEach { if (!it.stripBitmap.isRecycled) it.stripBitmap.recycle() }
        }
    }

    fun updatePendingSlot(tempId: String, transform: (PendingSlotDraft) -> PendingSlotDraft) {
        _uiState.update { state ->
            state.copy(
                pendingSlots = state.pendingSlots.map { if (it.tempId == tempId) transform(it) else it }
            )
        }
    }

    fun commitPendingSlots() {
        val pending = _uiState.value.pendingSlots.filter { it.include }
        if (pending.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "Select at least one cropped slot to save.", statusIsError = true) }
            return
        }
        scope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "Saving ${pending.size} skill keys...") }
            val newKeys = mutableListOf<ScannedSkillKey>()
            for (draft in pending) {
                val (keyCode, keyLabel) = ScannedSkillKey.keyCodeForSlotIndex(draft.slotIndex)
                val tpl = repository.saveTemplateBitmap(
                    name = "${draft.owner.shortLabel}_${draft.label.ifBlank { "Slot$keyLabel" }}",
                    bitmap = draft.iconBitmap
                )
                newKeys.add(
                    ScannedSkillKey(
                        owner = draft.owner,
                        label = draft.label.trim().ifBlank { "Slot $keyLabel" },
                        category = draft.category,
                        keyCode = keyCode,
                        keyLabel = keyLabel,
                        metaState = draft.groupKey.metaState,
                        barPage = draft.barPage,
                        groupKey = draft.groupKey,
                        slotIndex = draft.slotIndex,
                        iconTemplateId = tpl.id
                    )
                )
            }

            val allOldPending = _uiState.value.pendingSlots
            val templatePaths = repository.getTemplatePathMap()
            hasUserEditedProfile = true
            var persistedProfile: FlyffProfile? = null
            _uiState.update { state ->
                val merged = mergeSkillKeys(state.profile.skillKeys, newKeys)
                val autoWiredProfile = autoLinkMatchingBuffs(
                    autoWireRulesFromSkills(state.profile.copy(skillKeys = merged)),
                    templatePaths = templatePaths
                ).copy(updatedAt = System.currentTimeMillis())
                persistedProfile = autoWiredProfile
                state.copy(
                    profile = autoWiredProfile,
                    pendingSlots = emptyList(),
                    isBusy = false,
                    statusMessage = "Saved ${newKeys.size} key(s) to profile (${merged.size} total).",
                    statusIsError = false
                )
            }
            persistedProfile?.let {
                repository.saveFlyffProfile(it)
                smartFlyffEngine.updateActiveProfile(it)
            }
            allOldPending.forEach { if (!it.iconBitmap.isRecycled) it.iconBitmap.recycle() }
        }
    }

    fun addOrUpdateManualSkillKey(skill: ScannedSkillKey) {
        _uiState.update { state ->
            val exists = state.profile.skillKeys.any { it.id == skill.id }
            val updated = if (exists) {
                state.profile.skillKeys.map { if (it.id == skill.id) skill else it }
            } else {
                state.profile.skillKeys + skill
            }
            val wired = autoWireRulesFromSkills(state.profile.copy(skillKeys = updated))
            state.copy(
                profile = wired.copy(updatedAt = System.currentTimeMillis()),
                statusMessage = "Saved key ${skill.label} (${skill.shortBadge()})",
                statusIsError = false
            )
        }
    }

    fun removeSkillKey(skillId: String) {
        _uiState.update { state ->
            val p = state.profile
            state.copy(
                profile = p.copy(
                    skillKeys = p.skillKeys.filterNot { it.id == skillId },
                    buffLinks = p.buffLinks.filterNot { it.skillKeyId == skillId },
                    vitalTriggers = p.vitalTriggers.filterNot { it.skillKeyId == skillId },
                    healRule = p.healRule.copy(
                        healSkillKeyId = if (p.healRule.healSkillKeyId == skillId) null else p.healRule.healSkillKeyId,
                        secondaryHealSkillKeyId = if (p.healRule.secondaryHealSkillKeyId == skillId) null else p.healRule.secondaryHealSkillKeyId
                    ),
                    combatConfig = p.combatConfig.copy(
                        attackSkillKeyIds = p.combatConfig.attackSkillKeyIds.filterNot { it == skillId },
                        cooldownSkills = p.combatConfig.cooldownSkills.filterNot { it.skillKeyId == skillId }
                    ),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Auto-detects Mobile UI HP, MP, FP bars + Active Buff Grid and runs ML Kit OCR on every
     * detected active buff icon to preview its live MM:SS timer.
     */
    fun processMobileHudCapture(
        frame: Bitmap,
        layoutMode: DualLayoutMode,
        acct1Rect: Rect,
        acct2Rect: Rect
    ) {
        scope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    selectedHudLayout = layoutMode,
                    statusMessage = "Calibrating ${layoutMode.shortLabel} HP/MP/FP bars & scanning active buffs...",
                    statusIsError = false
                )
            }

            val catalogEntries = catalogManager?.state?.value?.entries ?: FlyffBuiltinCatalog.entries
            val savedKeys = _uiState.value.profile.skillKeys
            val templatePaths = repository.getTemplatePathMap()

            val result = withContext(Dispatchers.Default) {
                val panes = resolveVisiblePanes(frame, layoutMode, acct1Rect, acct2Rect, ownerOverride = null)
                val charHuds = mutableMapOf<WebViewTarget, CharacterHudRegions>()
                val readouts = mutableMapOf<WebViewTarget, LiveVitalReadout>()
                val buffDrafts = mutableListOf<PendingBuffDraft>()

                for ((owner, paneRect) in panes) {
                    val hud = FlyffVisionAnalyzer.autoDetectMobileHudBars(frame, paneRect, owner)
                    charHuds[owner] = hud
                    readouts[owner] = computeVitalReadout(frame, hud)

                    val tiles = FlyffVisionAnalyzer.autoDetectActiveBuffTiles(frame, hud)
                    tiles.forEachIndexed { idx, tile ->
                        val reading = timerOcrReader.readTimerFromStrip(tile.timerStripBitmap, cacheKey = null)
                        if (!tile.timerStripBitmap.isRecycled) tile.timerStripBitmap.recycle()
                        val autoMatch = FlyffIconMatcher.identifyActiveBuffIcon(
                            fullTileBitmap = tile.fullTileBitmap,
                            artCropBitmap = tile.artCropBitmap,
                            catalogEntries = catalogEntries,
                            savedKeys = savedKeys,
                            templatePathById = templatePaths
                        )
                        buffDrafts.add(
                            PendingBuffDraft(
                                owner = owner,
                                row = tile.row,
                                col = tile.col,
                                label = autoMatch?.label ?: "Buff ${idx + 1}",
                                detectedTimerSec = reading.remainingSeconds,
                                detectedTimerRaw = reading.rawText,
                                include = true,
                                fullTileBitmap = tile.fullTileBitmap,
                                artCropBitmap = tile.artCropBitmap,
                                autoMatchConfidencePct = autoMatch?.confidencePct,
                                autoMatchSource = autoMatch?.sourceTag,
                                matchedSkillKeyId = autoMatch?.matchedSkillKeyId
                            )
                        )
                    }
                }

                val existingLayout = _uiState.value.profile.layouts[layoutMode]
                val calibration = LayoutHudCalibration(
                    layoutMode = layoutMode,
                    screenWidth = frame.width,
                    screenHeight = frame.height,
                    charHuds = charHuds,
                    attackToggleRegion = existingLayout?.attackToggleRegion,
                    attackToggleTemplateId = existingLayout?.attackToggleTemplateId,
                    capturedAt = System.currentTimeMillis()
                )
                Triple(calibration, readouts, buffDrafts)
            }

            val (calibration, readouts, buffDrafts) = result
            val oldPreview = _uiState.value.hudPreviewBitmap
            val oldBuffs = _uiState.value.pendingBuffs
            val autoNamed = buffDrafts.count { it.autoMatchConfidencePct != null }
            hasUserEditedProfile = true

            _uiState.update { state ->
                val updatedLayouts = state.profile.layouts + (layoutMode to calibration)
                state.copy(
                    profile = state.profile.copy(
                        layouts = updatedLayouts,
                        updatedAt = System.currentTimeMillis()
                    ),
                    selectedHudLayout = layoutMode,
                    hudPreviewBitmap = frame,
                    hudPreviewLayout = layoutMode,
                    liveVitalReadouts = readouts,
                    pendingBuffs = buffDrafts,
                    isBusy = false,
                    statusMessage = buildString {
                        append("Calibrated ${layoutMode.shortLabel} (${calibration.charHuds.size} char HUD, ${buffDrafts.size} active buff icon(s)")
                        if (autoNamed > 0) append(", $autoNamed auto-identified")
                        append(").")
                    },
                    statusIsError = false
                )
            }

            if (oldPreview != null && oldPreview !== frame && !oldPreview.isRecycled) {
                oldPreview.recycle()
            }
            oldBuffs.forEach {
                if (!it.fullTileBitmap.isRecycled) it.fullTileBitmap.recycle()
                if (!it.artCropBitmap.isRecycled) it.artCropBitmap.recycle()
            }
        }
    }

    fun nudgeHudRegion(
        layoutMode: DualLayoutMode,
        owner: WebViewTarget,
        target: HudNudgeTarget,
        dx: Int = 0,
        dy: Int = 0,
        dw: Int = 0,
        dh: Int = 0
    ) {
        val state = _uiState.value
        val layoutCal = state.profile.layouts[layoutMode] ?: return
        val hud = layoutCal.charHuds[owner] ?: return
        val screenW = layoutCal.screenWidth.coerceAtLeast(1)
        val screenH = layoutCal.screenHeight.coerceAtLeast(1)

        fun adjust(spec: RectSpec): RectSpec {
            val l = (spec.left + dx).coerceIn(0, screenW - 8)
            val t = (spec.top + dy).coerceIn(0, screenH - 4)
            val r = (spec.right + dx + dw).coerceIn(l + 8, screenW)
            val b = (spec.bottom + dy + dh).coerceIn(t + 4, screenH)
            return RectSpec.fromPixels(l, t, r, b, screenW, screenH)
        }

        val updatedHud = when (target) {
            HudNudgeTarget.HP -> hud.copy(hpBar = hud.hpBar?.let { it.copy(rect = adjust(it.rect)) })
            HudNudgeTarget.MP -> hud.copy(mpBar = hud.mpBar?.let { it.copy(rect = adjust(it.rect)) })
            HudNudgeTarget.FP -> hud.copy(fpBar = hud.fpBar?.let { it.copy(rect = adjust(it.rect)) })
            HudNudgeTarget.BUFF_GRID -> {
                val nextGrid = adjust(hud.buffGridRegion)
                val estTile = (nextGrid.width / 7).coerceIn(22, 100)
                hud.copy(buffGridRegion = nextGrid, estimatedTileWidth = estTile, estimatedTileHeight = estTile)
            }
        }

        val updatedCal = layoutCal.copy(charHuds = layoutCal.charHuds + (owner to updatedHud))
        val preview = state.hudPreviewBitmap
        val updatedReadouts = if (preview != null && !preview.isRecycled && state.hudPreviewLayout == layoutMode) {
            state.liveVitalReadouts + (owner to computeVitalReadout(preview, updatedHud))
        } else {
            state.liveVitalReadouts
        }

        _uiState.update {
            it.copy(
                profile = it.profile.copy(
                    layouts = it.profile.layouts + (layoutMode to updatedCal),
                    updatedAt = System.currentTimeMillis()
                ),
                liveVitalReadouts = updatedReadouts
            )
        }
    }

    fun reScanBuffTilesFromPreview(layoutMode: DualLayoutMode) {
        val state = _uiState.value
        val frame = state.hudPreviewBitmap ?: return
        if (frame.isRecycled || state.hudPreviewLayout != layoutMode) return
        val layoutCal = state.profile.layouts[layoutMode] ?: return

        scope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "Re-scanning Active Buff Grid...") }
            val catalogEntries = catalogManager?.state?.value?.entries ?: FlyffBuiltinCatalog.entries
            val savedKeys = _uiState.value.profile.skillKeys
            val templatePaths = repository.getTemplatePathMap()
            val buffDrafts = withContext(Dispatchers.Default) {
                val list = mutableListOf<PendingBuffDraft>()
                for ((owner, hud) in layoutCal.charHuds) {
                    val tiles = FlyffVisionAnalyzer.autoDetectActiveBuffTiles(frame, hud)
                    tiles.forEachIndexed { idx, tile ->
                        val reading = timerOcrReader.readTimerFromStrip(tile.timerStripBitmap, cacheKey = null)
                        if (!tile.timerStripBitmap.isRecycled) tile.timerStripBitmap.recycle()
                        val autoMatch = FlyffIconMatcher.identifyActiveBuffIcon(
                            fullTileBitmap = tile.fullTileBitmap,
                            artCropBitmap = tile.artCropBitmap,
                            catalogEntries = catalogEntries,
                            savedKeys = savedKeys,
                            templatePathById = templatePaths
                        )
                        list.add(
                            PendingBuffDraft(
                                owner = owner,
                                row = tile.row,
                                col = tile.col,
                                label = autoMatch?.label ?: "Buff ${idx + 1}",
                                detectedTimerSec = reading.remainingSeconds,
                                detectedTimerRaw = reading.rawText,
                                include = true,
                                fullTileBitmap = tile.fullTileBitmap,
                                artCropBitmap = tile.artCropBitmap,
                                autoMatchConfidencePct = autoMatch?.confidencePct,
                                autoMatchSource = autoMatch?.sourceTag,
                                matchedSkillKeyId = autoMatch?.matchedSkillKeyId
                            )
                        )
                    }
                }
                list
            }
            val old = _uiState.value.pendingBuffs
            val autoNamed = buffDrafts.count { it.autoMatchConfidencePct != null }
            _uiState.update {
                it.copy(
                    pendingBuffs = buffDrafts,
                    isBusy = false,
                    statusMessage = "Found ${buffDrafts.size} active buff icon(s)" +
                        if (autoNamed > 0) " ($autoNamed auto-identified)." else " in updated grid."
                )
            }
            old.forEach {
                if (!it.fullTileBitmap.isRecycled) it.fullTileBitmap.recycle()
                if (!it.artCropBitmap.isRecycled) it.artCropBitmap.recycle()
            }
        }
    }

    fun updatePendingBuff(tempId: String, transform: (PendingBuffDraft) -> PendingBuffDraft) {
        _uiState.update { state ->
            state.copy(
                pendingBuffs = state.pendingBuffs.map { if (it.tempId == tempId) transform(it) else it }
            )
        }
    }

    fun commitPendingBuffs(layoutMode: DualLayoutMode) {
        val selected = _uiState.value.pendingBuffs.filter { it.include }
        if (selected.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "Select at least one active buff icon to save.", statusIsError = true) }
            return
        }
        scope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "Saving ${selected.size} active buff templates...") }
            val currentBuffs = _uiState.value.profile.activeBuffs.toMutableList()
            val directVisualMatches = mutableListOf<Pair<String, String>>()

            for (draft in selected) {
                val cleanLabel = draft.label.trim().ifBlank { "Buff" }
                val artTpl = repository.saveTemplateBitmap(
                    name = "${draft.owner.shortLabel}_${cleanLabel}_${layoutMode.shortLabel}",
                    bitmap = draft.artCropBitmap
                )
                val previewTpl = repository.saveTemplateBitmap(
                    name = "${draft.owner.shortLabel}_${cleanLabel}_preview",
                    bitmap = draft.fullTileBitmap
                )
                val existingIdx = currentBuffs.indexOfFirst {
                    it.targetChar == draft.owner && it.label.equals(cleanLabel, ignoreCase = true)
                }
                val savedBuffId: String
                if (existingIdx >= 0) {
                    val existing = currentBuffs[existingIdx]
                    savedBuffId = existing.id
                    currentBuffs[existingIdx] = existing.copy(
                        perLayoutTemplateIds = existing.perLayoutTemplateIds + (layoutMode to artTpl.id),
                        previewTemplateId = existing.previewTemplateId ?: previewTpl.id,
                        lastDetectedSeconds = draft.detectedTimerSec ?: existing.lastDetectedSeconds
                    )
                } else {
                    val created = ActiveBuffDefinition(
                        targetChar = draft.owner,
                        label = cleanLabel,
                        perLayoutTemplateIds = mapOf(layoutMode to artTpl.id),
                        previewTemplateId = previewTpl.id,
                        lastDetectedSeconds = draft.detectedTimerSec
                    )
                    savedBuffId = created.id
                    currentBuffs.add(created)
                }
                draft.matchedSkillKeyId?.let { keyId ->
                    directVisualMatches.add(keyId to savedBuffId)
                }
            }

            val allOld = _uiState.value.pendingBuffs
            val templatePaths = repository.getTemplatePathMap()
            hasUserEditedProfile = true
            var persistedProfile: FlyffProfile? = null
            _uiState.update { state ->
                val withBuffs = state.profile.copy(
                    activeBuffs = currentBuffs,
                    updatedAt = System.currentTimeMillis()
                )
                val withAutoLinks = autoLinkMatchingBuffs(withBuffs, templatePaths, directVisualMatches)
                persistedProfile = withAutoLinks
                state.copy(
                    profile = withAutoLinks,
                    pendingBuffs = emptyList(),
                    isBusy = false,
                    statusMessage = "Saved ${selected.size} active buff icon(s) for ${layoutMode.shortLabel} (${withAutoLinks.buffLinks.size} buff link(s) ready).",
                    statusIsError = false
                )
            }
            persistedProfile?.let {
                repository.saveFlyffProfile(it)
                smartFlyffEngine.updateActiveProfile(it)
            }
            allOld.forEach {
                if (!it.fullTileBitmap.isRecycled) it.fullTileBitmap.recycle()
                if (!it.artCropBitmap.isRecycled) it.artCropBitmap.recycle()
            }
        }
    }

    fun removeActiveBuff(buffId: String) {
        _uiState.update { state ->
            val p = state.profile
            state.copy(
                profile = p.copy(
                    activeBuffs = p.activeBuffs.filterNot { it.id == buffId },
                    buffLinks = p.buffLinks.filterNot { it.activeBuffId == buffId },
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Captures a small on-screen Attack Toggle icon template from the given frame & rect (e.g.,
     * Flyff's action-bar or custom icon indicator used in psy fight).
     */
    fun captureAttackToggle(frame: Bitmap, cropRect: Rect, layoutMode: DualLayoutMode) {
        scope.launch {
            val crop = FlyffVisionAnalyzer.safeCrop(frame, cropRect)
            if (crop == null) {
                if (!frame.isRecycled) frame.recycle()
                _uiState.update { it.copy(statusMessage = "Unable to crop attack toggle region.", statusIsError = true) }
                return@launch
            }
            val tpl = repository.saveTemplateBitmap("AtkToggle_${layoutMode.shortLabel}", crop)
            crop.recycle()
            val roiSpec = RectSpec.fromPixels(
                left = (cropRect.left - 24).coerceAtLeast(0),
                top = (cropRect.top - 24).coerceAtLeast(0),
                right = (cropRect.right + 24).coerceAtMost(frame.width),
                bottom = (cropRect.bottom + 24).coerceAtMost(frame.height),
                screenW = frame.width,
                screenH = frame.height
            )
            if (!frame.isRecycled) frame.recycle()

            _uiState.update { state ->
                val existing = state.profile.layouts[layoutMode] ?: LayoutHudCalibration(layoutMode = layoutMode)
                val updated = existing.copy(
                    attackToggleRegion = roiSpec,
                    attackToggleTemplateId = tpl.id
                )
                state.copy(
                    profile = state.profile.copy(
                        layouts = state.profile.layouts + (layoutMode to updated),
                        combatConfig = state.profile.combatConfig.copy(
                            enabled = true,
                            useScreenToggleMatch = true
                        ),
                        updatedAt = System.currentTimeMillis()
                    ),
                    statusMessage = "Captured Attack Toggle icon for ${layoutMode.shortLabel}.",
                    statusIsError = false
                )
            }
        }
    }

    fun autoLinkBuffsByName() {
        scope.launch {
            val templatePaths = repository.getTemplatePathMap()
            val updated = withContext(Dispatchers.Default) {
                autoLinkMatchingBuffs(_uiState.value.profile, templatePaths)
            }
            _uiState.update { state ->
                val before = state.profile.buffLinks.size
                val added = updated.buffLinks.size - before
                state.copy(
                    profile = updated,
                    statusMessage = if (added > 0) "Auto-linked $added buff pair(s) by name & icon." else "All matching buff pairs are already linked.",
                    statusIsError = false
                )
            }
        }
    }

    fun addBuffLink(skillKeyId: String, activeBuffId: String, refreshBelowSec: Int = 90) {
        hasUserEditedProfile = true
        _uiState.update { state ->
            val existing = state.profile.buffLinks
            if (existing.any { it.skillKeyId == skillKeyId && it.activeBuffId == activeBuffId }) {
                return@update state.copy(statusMessage = "This key and active buff are already linked.", statusIsError = false)
            }
            val nextPriority = (existing.maxOfOrNull { it.priority } ?: 0) + 10
            val newLink = BuffKeyLink(
                skillKeyId = skillKeyId,
                activeBuffId = activeBuffId,
                refreshBelowSec = refreshBelowSec,
                priority = nextPriority
            )
            state.copy(
                profile = state.profile.copy(
                    buffLinks = existing + newLink,
                    updatedAt = System.currentTimeMillis()
                ),
                statusMessage = "Linked buff key to active buff icon.",
                statusIsError = false
            )
        }
    }

    fun updateBuffLink(updated: BuffKeyLink) {
        updateProfile { p ->
            p.copy(buffLinks = p.buffLinks.map { if (it.id == updated.id) updated else it })
        }
    }

    fun removeBuffLink(linkId: String) {
        updateProfile { p -> p.copy(buffLinks = p.buffLinks.filterNot { it.id == linkId }) }
    }

    fun updateHealRule(transform: (HealRuleConfig) -> HealRuleConfig) {
        updateProfile { it.copy(healRule = transform(it.healRule)) }
    }

    fun addVitalTrigger(rule: VitalTriggerRule) {
        updateProfile { it.copy(vitalTriggers = it.vitalTriggers + rule) }
    }

    fun updateVitalTrigger(rule: VitalTriggerRule) {
        updateProfile { p -> p.copy(vitalTriggers = p.vitalTriggers.map { if (it.id == rule.id) rule else it }) }
    }

    fun removeVitalTrigger(ruleId: String) {
        updateProfile { p -> p.copy(vitalTriggers = p.vitalTriggers.filterNot { it.id == ruleId }) }
    }

    fun updateCombatConfig(transform: (CombatRotationConfig) -> CombatRotationConfig) {
        updateProfile { it.copy(combatConfig = transform(it.combatConfig)) }
    }

    fun addCooldownSkill(rule: CooldownSkillRule) {
        updateCombatConfig { it.copy(cooldownSkills = it.cooldownSkills + rule) }
    }

    fun removeCooldownSkill(ruleId: String) {
        updateCombatConfig { it.copy(cooldownSkills = it.cooldownSkills.filterNot { r -> r.id == ruleId }) }
    }

    /**
     * Automatically commits any checked pending cropped slots (Step 2) AND checked pending active
     * buffs (Step 3) into the profile so pressing the bottom Save, Export Macro, or Save & Run
     * button NEVER discards scanned items.
     */
    private suspend fun commitPendingDraftsIntoProfile(layoutMode: DualLayoutMode): FlyffProfile {
        val snapshot = _uiState.value
        val checkedSlots = snapshot.pendingSlots.filter { it.include && !it.iconBitmap.isRecycled }
        val checkedBuffs = snapshot.pendingBuffs.filter {
            it.include && !it.artCropBitmap.isRecycled && !it.fullTileBitmap.isRecycled
        }

        var workingProfile = snapshot.profile.copy(
            name = snapshot.profile.name.trim().ifBlank { "Flyff Smart Profile" },
            updatedAt = System.currentTimeMillis()
        )

        if (checkedSlots.isNotEmpty()) {
            val newKeys = mutableListOf<ScannedSkillKey>()
            for (draft in checkedSlots) {
                val (keyCode, keyLabel) = ScannedSkillKey.keyCodeForSlotIndex(draft.slotIndex)
                val tpl = repository.saveTemplateBitmap(
                    name = "${draft.owner.shortLabel}_${draft.label.ifBlank { "Slot$keyLabel" }}",
                    bitmap = draft.iconBitmap
                )
                newKeys.add(
                    ScannedSkillKey(
                        owner = draft.owner,
                        label = draft.label.trim().ifBlank { "Slot $keyLabel" },
                        category = draft.category,
                        keyCode = keyCode,
                        keyLabel = keyLabel,
                        metaState = draft.groupKey.metaState,
                        barPage = draft.barPage,
                        groupKey = draft.groupKey,
                        slotIndex = draft.slotIndex,
                        iconTemplateId = tpl.id
                    )
                )
            }
            val mergedKeys = mergeSkillKeys(workingProfile.skillKeys, newKeys)
            workingProfile = autoWireRulesFromSkills(workingProfile.copy(skillKeys = mergedKeys))
        }

        val directVisualMatches = mutableListOf<Pair<String, String>>() // skillKeyId to activeBuffId
        if (checkedBuffs.isNotEmpty()) {
            val currentBuffs = workingProfile.activeBuffs.toMutableList()
            for (draft in checkedBuffs) {
                val cleanLabel = draft.label.trim().ifBlank { "Buff" }
                val artTpl = repository.saveTemplateBitmap(
                    name = "${draft.owner.shortLabel}_${cleanLabel}_${layoutMode.shortLabel}",
                    bitmap = draft.artCropBitmap
                )
                val previewTpl = repository.saveTemplateBitmap(
                    name = "${draft.owner.shortLabel}_${cleanLabel}_preview",
                    bitmap = draft.fullTileBitmap
                )
                val existingIdx = currentBuffs.indexOfFirst {
                    it.targetChar == draft.owner && it.label.equals(cleanLabel, ignoreCase = true)
                }
                val savedBuffId: String
                if (existingIdx >= 0) {
                    val existing = currentBuffs[existingIdx]
                    savedBuffId = existing.id
                    currentBuffs[existingIdx] = existing.copy(
                        perLayoutTemplateIds = existing.perLayoutTemplateIds + (layoutMode to artTpl.id),
                        previewTemplateId = existing.previewTemplateId ?: previewTpl.id,
                        lastDetectedSeconds = draft.detectedTimerSec ?: existing.lastDetectedSeconds
                    )
                } else {
                    val created = ActiveBuffDefinition(
                        targetChar = draft.owner,
                        label = cleanLabel,
                        perLayoutTemplateIds = mapOf(layoutMode to artTpl.id),
                        previewTemplateId = previewTpl.id,
                        lastDetectedSeconds = draft.detectedTimerSec
                    )
                    savedBuffId = created.id
                    currentBuffs.add(created)
                }
                draft.matchedSkillKeyId?.let { keyId ->
                    directVisualMatches.add(keyId to savedBuffId)
                }
            }
            workingProfile = workingProfile.copy(activeBuffs = currentBuffs)
        }

        if (checkedSlots.isNotEmpty() || checkedBuffs.isNotEmpty()) {
            val templatePaths = repository.getTemplatePathMap()
            workingProfile = autoLinkMatchingBuffs(workingProfile, templatePaths, directVisualMatches)
        }

        val oldSlots = snapshot.pendingSlots
        val oldBuffs = snapshot.pendingBuffs
        hasUserEditedProfile = true
        _uiState.update { state ->
            state.copy(
                profile = workingProfile,
                pendingSlots = if (checkedSlots.isNotEmpty()) emptyList() else state.pendingSlots,
                pendingBuffs = if (checkedBuffs.isNotEmpty()) emptyList() else state.pendingBuffs
            )
        }
        if (checkedSlots.isNotEmpty()) {
            oldSlots.forEach { if (!it.iconBitmap.isRecycled) it.iconBitmap.recycle() }
        }
        if (checkedBuffs.isNotEmpty()) {
            oldBuffs.forEach {
                if (!it.fullTileBitmap.isRecycled) it.fullTileBitmap.recycle()
                if (!it.artCropBitmap.isRecycled) it.artCropBitmap.recycle()
            }
        }
        return workingProfile
    }

    fun saveProfile(onSaved: (FlyffProfile) -> Unit = {}) {
        scope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "Saving profile...") }
            val current = commitPendingDraftsIntoProfile(_uiState.value.selectedHudLayout)
            repository.saveFlyffProfile(current)
            smartFlyffEngine.updateActiveProfile(current)
            _uiState.update {
                it.copy(
                    profile = current,
                    isBusy = false,
                    statusMessage = "Saved '${current.name}' (${current.skillKeys.size} keys · ${current.activeBuffs.size} buffs · ${current.buffLinks.size} links).",
                    statusIsError = false
                )
            }
            onSaved(current)
        }
    }

    fun exportToMacro(
        layoutMode: DualLayoutMode? = null,
        onExported: (MacroDefinition) -> Unit = {}
    ) {
        scope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "Saving & exporting macro...") }
            val current = commitPendingDraftsIntoProfile(layoutMode ?: _uiState.value.selectedHudLayout)
            val targetLayout = layoutMode
                ?: current.layouts.keys.firstOrNull { it == _uiState.value.selectedHudLayout }
                ?: current.layouts.keys.firstOrNull()
                ?: DualLayoutMode.SPLIT_50_50
            repository.saveFlyffProfile(current)
            smartFlyffEngine.updateActiveProfile(current)
            val macro = FlyffMacroCompiler.compileToMacro(current, targetLayout)
            repository.saveMacro(macro)
            _uiState.update {
                it.copy(
                    profile = current,
                    isBusy = false,
                    statusMessage = "Exported '${macro.name}' to Macro Editor (${macro.functions.size} functions).",
                    statusIsError = false
                )
            }
            onExported(macro)
        }
    }

    fun dispose() {
        val s = _uiState.value
        s.pendingSlots.forEach { if (!it.iconBitmap.isRecycled) it.iconBitmap.recycle() }
        s.taskbarPreviews.forEach { if (!it.stripBitmap.isRecycled) it.stripBitmap.recycle() }
        s.pendingBuffs.forEach {
            if (!it.fullTileBitmap.isRecycled) it.fullTileBitmap.recycle()
            if (!it.artCropBitmap.isRecycled) it.artCropBitmap.recycle()
        }
        s.hudPreviewBitmap?.let { if (!it.isRecycled) it.recycle() }
    }

    private fun resolveVisiblePanes(
        frame: Bitmap,
        layoutMode: DualLayoutMode,
        acct1Rect: Rect,
        acct2Rect: Rect,
        ownerOverride: WebViewTarget?
    ): List<Pair<WebViewTarget, Rect>> {
        val fullRect = Rect(0, 0, frame.width, frame.height)
        val validA1 = if (acct1Rect.width() > 60 && acct1Rect.height() > 60) acct1Rect else null
        val validA2 = if (acct2Rect.width() > 60 && acct2Rect.height() > 60) acct2Rect else null

        val raw: List<Pair<WebViewTarget, Rect>> = when (layoutMode) {
            DualLayoutMode.SINGLE_ACCT_1 -> listOf(WebViewTarget.ACCOUNT_1 to (validA1 ?: fullRect))
            DualLayoutMode.SINGLE_ACCT_2 -> listOf(WebViewTarget.ACCOUNT_2 to (validA2 ?: fullRect))
            DualLayoutMode.SPLIT_50_50 -> {
                val isLandscape = frame.width >= frame.height
                val fallbackA2 = if (isLandscape) Rect(0, 0, frame.width / 2, frame.height) else Rect(0, 0, frame.width, frame.height / 2)
                val fallbackA1 = if (isLandscape) Rect(frame.width / 2, 0, frame.width, frame.height) else Rect(0, frame.height / 2, frame.width, frame.height)
                listOf(
                    WebViewTarget.ACCOUNT_2 to (validA2 ?: fallbackA2),
                    WebViewTarget.ACCOUNT_1 to (validA1 ?: fallbackA1)
                )
            }
        }
        return if (ownerOverride == null || ownerOverride == WebViewTarget.DEFAULT) {
            raw
        } else {
            val filtered = raw.filter { it.first == ownerOverride }
            if (filtered.isNotEmpty()) filtered else listOf(ownerOverride to fullRect)
        }
    }

    private fun computeVitalReadout(frame: Bitmap, hud: CharacterHudRegions): LiveVitalReadout =
        LiveVitalReadout(
            hpPct = hud.hpBar?.let { FlyffVisionAnalyzer.measureBarFillPercent(frame, it) },
            mpPct = hud.mpBar?.let { FlyffVisionAnalyzer.measureBarFillPercent(frame, it) },
            fpPct = hud.fpBar?.let { FlyffVisionAnalyzer.measureBarFillPercent(frame, it) }
        )

    private fun suggestDefaultCategory(owner: WebViewTarget, groupKey: TaskbarGroupKey, slotIndex: Int): SkillCategory =
        when (owner) {
            WebViewTarget.ACCOUNT_2 -> when {
                groupKey == TaskbarGroupKey.MAIN_1_0 && slotIndex == 0 -> SkillCategory.HEAL
                else -> SkillCategory.PARTY_BUFF
            }
            WebViewTarget.ACCOUNT_1 -> when {
                groupKey == TaskbarGroupKey.MAIN_1_0 && slotIndex == 9 -> SkillCategory.ATTACK_AOE
                groupKey == TaskbarGroupKey.MAIN_1_0 && slotIndex == 2 -> SkillCategory.MP_POT
                else -> SkillCategory.SELF_BUFF
            }
            WebViewTarget.DEFAULT -> SkillCategory.UTILITY
        }

    private fun mergeSkillKeys(
        existing: List<ScannedSkillKey>,
        incoming: List<ScannedSkillKey>
    ): List<ScannedSkillKey> {
        val result = existing.toMutableList()
        for (item in incoming) {
            val idx = result.indexOfFirst {
                it.owner == item.owner &&
                    it.keyCode == item.keyCode &&
                    it.metaState == item.metaState &&
                    it.barPage == item.barPage
            }
            if (idx >= 0) {
                result[idx] = item.copy(id = result[idx].id)
            } else {
                result.add(item)
            }
        }
        return result
    }

    private fun autoWireRulesFromSkills(profile: FlyffProfile): FlyffProfile {
        var healRule = profile.healRule
        if (healRule.healSkillKeyId == null) {
            val healKey = profile.skillKeys.firstOrNull { it.category == SkillCategory.HEAL }
            if (healKey != null) {
                healRule = healRule.copy(healSkillKeyId = healKey.id)
            }
        }

        val triggers = profile.vitalTriggers.toMutableList()
        fun ensurePotRule(cat: SkillCategory, vital: VitalType, defaultPct: Int) {
            profile.skillKeys.filter { it.category == cat }.forEach { key ->
                if (triggers.none { it.skillKeyId == key.id }) {
                    triggers.add(
                        VitalTriggerRule(
                            targetChar = key.owner,
                            vitalType = vital,
                            triggerBelowPct = defaultPct,
                            skillKeyId = key.id,
                            cooldownMs = 1500L
                        )
                    )
                }
            }
        }
        ensurePotRule(SkillCategory.HP_POT, VitalType.HP, 65)
        ensurePotRule(SkillCategory.MP_POT, VitalType.MP, 40)
        ensurePotRule(SkillCategory.FP_POT, VitalType.FP, 35)

        var combat = profile.combatConfig
        val atkKeys = profile.skillKeys.filter { it.category == SkillCategory.ATTACK_AOE }.map { it.id }
        if (combat.attackSkillKeyIds.isEmpty() && atkKeys.isNotEmpty()) {
            combat = combat.copy(attackSkillKeyIds = atkKeys)
        }
        val cdKeys = profile.skillKeys.filter { it.category == SkillCategory.COOLDOWN_SKILL }
        val existingCdIds = combat.cooldownSkills.map { it.skillKeyId }.toSet()
        val newCds = cdKeys.filter { it.id !in existingCdIds }.map {
            CooldownSkillRule(skillKeyId = it.id, cooldownMs = 8000L)
        }
        if (newCds.isNotEmpty()) {
            combat = combat.copy(cooldownSkills = combat.cooldownSkills + newCds)
        }

        return autoLinkMatchingBuffs(
            profile.copy(
                healRule = healRule,
                vitalTriggers = triggers,
                combatConfig = combat
            )
        )
    }

    private fun autoLinkMatchingBuffs(
        profile: FlyffProfile,
        templatePaths: Map<String, String> = emptyMap(),
        directVisualMatches: List<Pair<String, String>> = emptyList()
    ): FlyffProfile {
        val buffKeys = profile.skillKeys.filter {
            it.category == SkillCategory.PARTY_BUFF || it.category == SkillCategory.SELF_BUFF
        }
        val existingLinks = profile.buffLinks.toMutableList()
        var nextPriority = (existingLinks.maxOfOrNull { it.priority } ?: 0) + 10

        // 1. Direct key<->buff matches identified visually during Step 3 capture
        for ((keyId, buffId) in directVisualMatches) {
            if (buffKeys.any { it.id == keyId } && profile.activeBuffs.any { it.id == buffId }) {
                if (existingLinks.none { it.skillKeyId == keyId && it.activeBuffId == buffId }) {
                    existingLinks.add(
                        BuffKeyLink(
                            skillKeyId = keyId,
                            activeBuffId = buffId,
                            refreshBelowSec = 90,
                            priority = nextPriority
                        )
                    )
                    nextPriority += 10
                }
            }
        }

        // 2. Match by label (case-insensitive)
        for (key in buffKeys) {
            val matchingBuff = profile.activeBuffs.firstOrNull { buff ->
                buff.label.equals(key.label, ignoreCase = true) &&
                    (key.category == SkillCategory.PARTY_BUFF || buff.targetChar == key.owner)
            } ?: continue

            val already = existingLinks.any { it.skillKeyId == key.id && it.activeBuffId == matchingBuff.id }
            if (!already) {
                existingLinks.add(
                    BuffKeyLink(
                        skillKeyId = key.id,
                        activeBuffId = matchingBuff.id,
                        refreshBelowSec = 90,
                        priority = nextPriority
                    )
                )
                nextPriority += 10
            }
        }

        // 3. Visual upper-art icon fallback for any unlinked buff keys when template PNGs exist
        if (templatePaths.isNotEmpty()) {
            for (key in buffKeys) {
                if (existingLinks.any { it.skillKeyId == key.id }) continue
                val keyPath = key.iconTemplateId?.let { templatePaths[it] } ?: continue
                var bestBuffId: String? = null
                var bestScore = 0
                for (buff in profile.activeBuffs) {
                    if (key.category == SkillCategory.SELF_BUFF && buff.targetChar != key.owner) continue
                    val buffTplId = buff.previewTemplateId ?: buff.perLayoutTemplateIds.values.firstOrNull() ?: continue
                    val buffPath = templatePaths[buffTplId] ?: continue
                    val score = FlyffIconMatcher.compareIconFilesUpperArt(keyPath, buffPath)
                    if (score > bestScore) {
                        bestScore = score
                        bestBuffId = buff.id
                    }
                }
                if (bestBuffId != null && bestScore >= 68 &&
                    existingLinks.none { it.skillKeyId == key.id && it.activeBuffId == bestBuffId }
                ) {
                    existingLinks.add(
                        BuffKeyLink(
                            skillKeyId = key.id,
                            activeBuffId = bestBuffId,
                            refreshBelowSec = 90,
                            priority = nextPriority
                        )
                    )
                    nextPriority += 10
                }
            }
        }

        return profile.copy(buffLinks = existingLinks)
    }
}

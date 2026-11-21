package com.example.flyff

import android.graphics.Bitmap
import com.example.data.LogLevel
import com.example.data.MacroRepository
import com.example.dualweb.DualLayoutMode
import com.example.dualweb.DualWebInputBridge
import com.example.engine.ExecutionStatus
import com.example.model.WebViewTarget
import com.example.vision.ScreenDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class SmartEnginePhase(val label: String) {
    IDLE("Idle"),
    HEALING("Priority Heal"),
    POTION("Vital Pot"),
    BUFFING("Safe Buff"),
    COMBAT("Combat"),
    SAFE_WATCH("Watching"),
    UNCONFIGURED_LAYOUT("Calibrate View")
}

data class BuffRuntimeStatus(
    val linkId: String,
    val buffName: String,
    val targetChar: WebViewTarget,
    val detected: Boolean,
    val remainingSeconds: Int?,
    val needsRefresh: Boolean
)

data class SmartFlyffRuntimeState(
    val status: ExecutionStatus = ExecutionStatus.IDLE,
    val activeProfileId: String? = null,
    val activeProfileName: String = "",
    val activeLayoutMode: DualLayoutMode = DualLayoutMode.SPLIT_50_50,
    val layoutCalibrated: Boolean = true,
    val phase: SmartEnginePhase = SmartEnginePhase.IDLE,
    val a1HpPct: Int? = null,
    val a1MpPct: Int? = null,
    val a1FpPct: Int? = null,
    val a2HpPct: Int? = null,
    val a2MpPct: Int? = null,
    val a2FpPct: Int? = null,
    val manualCombatEnabled: Boolean = false,
    val screenToggleMatched: Boolean = false,
    val nextBuffSummary: String = "",
    val buffStatuses: Map<String, BuffRuntimeStatus> = emptyMap(),
    val lastTelemetry: String = "Ready",
    val errorMessage: String? = null
)

/**
 * Real-time priority combat & buff engine for Dual Flyff Universe:
 * 1. Auto-adapts live to Single (A1 / A2) or 50/50 Split layout without restarting
 * 2. Priority 1: Always preempts for HP Healing
 * 3. Priority 2: Instant HP / MP / FP pot & food % triggers (A1 & A2)
 * 4. Priority 3: Safe-HP one-at-a-time timed buff refresh using OCR timers (missing first, then lowest timer)
 * 5. Priority 4: Fighter self-buffs, Attack toggle & timed cooldown / AoE skills
 */
class SmartFlyffEngine(
    private val repository: MacroRepository,
    private val screenDetector: ScreenDetector,
    private val dualWebInputBridge: DualWebInputBridge,
    private val timerOcrReader: BuffTimerOcrReader = BuffTimerOcrReader()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null

    @Volatile
    private var activeProfile: FlyffProfile? = null

    @Volatile
    private var manualCombatOverride: Boolean = false

    private val _state = MutableStateFlow(SmartFlyffRuntimeState())
    val state: StateFlow<SmartFlyffRuntimeState> = _state.asStateFlow()

    private val lastPageByAccount = ConcurrentHashMap<WebViewTarget, Int>()
    private val lastVitalTriggerMs = ConcurrentHashMap<String, Long>()
    private val lastBuffCastMs = ConcurrentHashMap<String, Long>()
    private val lastCooldownSkillMs = ConcurrentHashMap<String, Long>()
    private var lastAttackMs: Long = 0L
    private var lastBuffScanMs: Long = 0L
    private var consecutiveSafeHpTicks: Int = 0
    private var attackKeyRoundRobin: Int = 0
    private val liveHudOverrides = ConcurrentHashMap<Pair<DualLayoutMode, WebViewTarget>, CharacterHudRegions>()

    fun start(profile: FlyffProfile) {
        stop()
        if (!dualWebInputBridge.isRoutingActive()) {
            val msg = "Open Dual Flyff first to run Smart Profile."
            _state.value = SmartFlyffRuntimeState(
                status = ExecutionStatus.ERROR,
                activeProfileId = profile.id,
                activeProfileName = profile.name,
                errorMessage = msg,
                lastTelemetry = msg
            )
            return
        }
        if (!screenDetector.isActive.value) {
            val msg = "Grant Screen Capture first so Smart Flyff can read HP/MP/FP bars and buff timers."
            _state.value = SmartFlyffRuntimeState(
                status = ExecutionStatus.ERROR,
                activeProfileId = profile.id,
                activeProfileName = profile.name,
                errorMessage = msg,
                lastTelemetry = msg
            )
            return
        }

        activeProfile = profile
        manualCombatOverride = profile.combatConfig.manualCombatEnabled
        lastPageByAccount.clear()
        lastVitalTriggerMs.clear()
        lastBuffCastMs.clear()
        lastCooldownSkillMs.clear()
        timerOcrReader.clearCache()
        lastBuffScanMs = 0L
        lastAttackMs = 0L
        consecutiveSafeHpTicks = 0
        attackKeyRoundRobin = 0
        liveHudOverrides.clear()

        _state.value = SmartFlyffRuntimeState(
            status = ExecutionStatus.RUNNING,
            activeProfileId = profile.id,
            activeProfileName = profile.name,
            activeLayoutMode = dualWebInputBridge.currentLayoutMode(),
            phase = SmartEnginePhase.SAFE_WATCH,
            manualCombatEnabled = manualCombatOverride,
            lastTelemetry = "Smart Flyff running (${profile.name})"
        )

        runJob = scope.launch {
            repository.appendLog(
                profile.id,
                profile.name,
                "SmartFlyff",
                "Started Smart Profile (${profile.skillKeys.size} keys, ${profile.buffLinks.size} buff links)",
                LogLevel.INFO
            )
            executeLoop()
        }
    }

    fun updateActiveProfile(profile: FlyffProfile) {
        if (activeProfile?.id == profile.id) {
            activeProfile = profile
            _state.update { it.copy(activeProfileName = profile.name) }
        }
    }

    fun toggleManualCombat() {
        manualCombatOverride = !manualCombatOverride
        _state.update {
            it.copy(
                manualCombatEnabled = manualCombatOverride,
                lastTelemetry = if (manualCombatOverride) "Combat ATK ON" else "Combat ATK OFF"
            )
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        activeProfile = null
        _state.update {
            it.copy(
                status = ExecutionStatus.IDLE,
                phase = SmartEnginePhase.IDLE,
                lastTelemetry = "Stopped"
            )
        }
    }

    private suspend fun executeLoop() {
        val buffStatusMap = mutableMapOf<String, BuffRuntimeStatus>()
        try {
            while (scope.isActive) {
                val profile = activeProfile ?: break
                if (!dualWebInputBridge.isRoutingActive() || !screenDetector.isActive.value) {
                    val reason = if (!dualWebInputBridge.isRoutingActive()) "Dual Flyff closed" else "Screen capture stopped"
                    _state.update {
                        it.copy(
                            status = ExecutionStatus.IDLE,
                            phase = SmartEnginePhase.IDLE,
                            lastTelemetry = reason
                        )
                    }
                    break
                }

                val currentLayout = dualWebInputBridge.currentLayoutMode()
                val frame = screenDetector.captureLatestBitmap()
                if (frame == null) {
                    delay(profile.pollingIntervalMs.coerceIn(80L, 400L))
                    continue
                }

                try {
                    val now = System.currentTimeMillis()
                    val (liveA1Pane, liveA2Pane) = dualWebInputBridge.currentPaneRects()
                    val calibratedLayout = profile.layouts[currentLayout]

                    // Resolve each visible character's HUD (calibrated -> live re-anchored -> on-the-fly auto-detected!)
                    fun resolveLiveHud(target: WebViewTarget, paneRect: android.graphics.Rect): CharacterHudRegions? {
                        val visibleInLayout = when (currentLayout) {
                            DualLayoutMode.SINGLE_ACCT_1 -> target == WebViewTarget.ACCOUNT_1
                            DualLayoutMode.SINGLE_ACCT_2 -> target == WebViewTarget.ACCOUNT_2
                            DualLayoutMode.SPLIT_50_50 -> true
                        }
                        if (!visibleInLayout) return null

                        val cacheKey = currentLayout to target
                        val existing = liveHudOverrides[cacheKey]
                            ?: calibratedLayout?.charHuds?.get(target)
                        if (existing != null) return existing

                        // Dynamic zero-setup fallback: auto-detect HUD bars right from the live frame!
                        val fallbackPane = if (paneRect.width() > 80 && paneRect.height() > 80) {
                            paneRect
                        } else {
                            when (currentLayout) {
                                DualLayoutMode.SINGLE_ACCT_1,
                                DualLayoutMode.SINGLE_ACCT_2 -> android.graphics.Rect(0, 0, frame.width, frame.height)
                                DualLayoutMode.SPLIT_50_50 -> {
                                    val isLandscape = frame.width >= frame.height
                                    if (target == WebViewTarget.ACCOUNT_2) {
                                        if (isLandscape) android.graphics.Rect(0, 0, frame.width / 2, frame.height)
                                        else android.graphics.Rect(0, 0, frame.width, frame.height / 2)
                                    } else {
                                        if (isLandscape) android.graphics.Rect(frame.width / 2, 0, frame.width, frame.height)
                                        else android.graphics.Rect(0, frame.height / 2, frame.width, frame.height)
                                    }
                                }
                            }
                        }
                        val autoDetected = FlyffVisionAnalyzer.autoDetectMobileHudBars(frame, fallbackPane, target)
                        liveHudOverrides[cacheKey] = autoDetected
                        return autoDetected
                    }

                    val a1BaseHud = resolveLiveHud(WebViewTarget.ACCOUNT_1, liveA1Pane)
                    val a2BaseHud = resolveLiveHud(WebViewTarget.ACCOUNT_2, liveA2Pane)

                    // 1. Dynamically read HP / MP / FP % with automatic drift re-anchoring (< 1ms fast path)
                    val a1Measure = a1BaseHud?.let {
                        FlyffVisionAnalyzer.measureHudVitalsDynamic(frame, it, liveA1Pane.takeIf { r -> r.width() > 80 })
                    }
                    if (a1Measure != null && a1Measure.reanchored) {
                        liveHudOverrides[currentLayout to WebViewTarget.ACCOUNT_1] = a1Measure.activeHud
                    }
                    val a2Measure = a2BaseHud?.let {
                        FlyffVisionAnalyzer.measureHudVitalsDynamic(frame, it, liveA2Pane.takeIf { r -> r.width() > 80 })
                    }
                    if (a2Measure != null && a2Measure.reanchored) {
                        liveHudOverrides[currentLayout to WebViewTarget.ACCOUNT_2] = a2Measure.activeHud
                    }

                    val a1Hud = a1Measure?.activeHud ?: a1BaseHud
                    val a2Hud = a2Measure?.activeHud ?: a2BaseHud
                    val a1Hp = a1Measure?.hpPct
                    val a1Mp = a1Measure?.mpPct
                    val a1Fp = a1Measure?.fpPct
                    val a2Hp = a2Measure?.hpPct
                    val a2Mp = a2Measure?.mpPct
                    val a2Fp = a2Measure?.fpPct

                    val activeLayoutCal = calibratedLayout?.copy(
                        charHuds = buildMap {
                            putAll(calibratedLayout.charHuds)
                            if (a1Hud != null) put(WebViewTarget.ACCOUNT_1, a1Hud)
                            if (a2Hud != null) put(WebViewTarget.ACCOUNT_2, a2Hud)
                        }
                    ) ?: LayoutHudCalibration(
                        layoutMode = currentLayout,
                        screenWidth = frame.width,
                        screenHeight = frame.height,
                        charHuds = buildMap {
                            if (a1Hud != null) put(WebViewTarget.ACCOUNT_1, a1Hud)
                            if (a2Hud != null) put(WebViewTarget.ACCOUNT_2, a2Hud)
                        }
                    )

                    fun vitalPctFor(target: WebViewTarget, type: VitalType): Int? = when (target) {
                        WebViewTarget.ACCOUNT_1 -> when (type) {
                            VitalType.HP -> a1Hp
                            VitalType.MP -> a1Mp
                            VitalType.FP -> a1Fp
                        }
                        WebViewTarget.ACCOUNT_2 -> when (type) {
                            VitalType.HP -> a2Hp
                            VitalType.MP -> a2Mp
                            VitalType.FP -> a2Fp
                        }
                        WebViewTarget.DEFAULT -> a1Hp
                    }

                    _state.update {
                        it.copy(
                            activeLayoutMode = currentLayout,
                            layoutCalibrated = calibratedLayout != null,
                            a1HpPct = a1Hp,
                            a1MpPct = a1Mp,
                            a1FpPct = a1Fp,
                            a2HpPct = a2Hp,
                            a2MpPct = a2Mp,
                            a2FpPct = a2Fp,
                            manualCombatEnabled = manualCombatOverride
                        )
                    }

                    // 2. PRIORITY 1: HEALING ALWAYS FIRST (never blocks Priority 2 emergency HP/MP/FP pots!)
                    var healedThisTick = false
                    val healRule = profile.healRule
                    val primaryHp = vitalPctFor(healRule.targetChar, VitalType.HP)
                    if (healRule.enabled && primaryHp != null && primaryHp < healRule.triggerBelowPct) {
                        consecutiveSafeHpTicks = 0
                        val healSkill = profile.skillById(healRule.healSkillKeyId)
                            ?: profile.skillKeys.firstOrNull { it.category == SkillCategory.HEAL }
                        if (healSkill != null) {
                            dispatchSkillKey(healSkill)
                            healedThisTick = true
                            _state.update {
                                it.copy(
                                    phase = SmartEnginePhase.HEALING,
                                    lastTelemetry = "HEAL ${healRule.targetChar.shortLabel} ($primaryHp% < ${healRule.triggerBelowPct}%)"
                                )
                            }
                        }
                    }

                    // Optional secondary heal (e.g. RM self-heal in split view)
                    val secondaryHp = vitalPctFor(healRule.secondaryTargetChar, VitalType.HP)
                    if (!healedThisTick && healRule.secondaryEnabled && secondaryHp != null && secondaryHp < healRule.secondaryTriggerBelowPct) {
                        val secSkill = profile.skillById(healRule.secondaryHealSkillKeyId)
                            ?: profile.skillKeys.firstOrNull { it.category == SkillCategory.HEAL && it.owner == healRule.secondaryTargetChar }
                            ?: profile.skillKeys.firstOrNull { it.category == SkillCategory.HEAL }
                        if (secSkill != null) {
                            dispatchSkillKey(secSkill)
                            healedThisTick = true
                            _state.update {
                                it.copy(
                                    phase = SmartEnginePhase.HEALING,
                                    lastTelemetry = "SELF-HEAL ${healRule.secondaryTargetChar.shortLabel} ($secondaryHp%)"
                                )
                            }
                        }
                    }

                    // 3. PRIORITY 2: INSTANT HP / MP / FP POT & FOOD TRIGGERS
                    // Always evaluated even while healing so Fighter HP food/pills & RM MP pots fire immediately!
                    var firedPot = false
                    for (rule in profile.vitalTriggers) {
                        if (!rule.enabled) continue
                        val currentPct = vitalPctFor(rule.targetChar, rule.vitalType) ?: continue
                        if (currentPct < rule.triggerBelowPct) {
                            val lastFired = lastVitalTriggerMs[rule.id] ?: 0L
                            if (now - lastFired >= rule.cooldownMs.coerceAtLeast(200L)) {
                                val skill = profile.skillById(rule.skillKeyId)
                                if (skill != null) {
                                    dispatchSkillKey(skill)
                                    lastVitalTriggerMs[rule.id] = now
                                    firedPot = true
                                    _state.update {
                                        it.copy(
                                            phase = if (healedThisTick) SmartEnginePhase.HEALING else SmartEnginePhase.POTION,
                                            lastTelemetry = "${rule.targetChar.shortLabel} ${rule.vitalType.shortLabel} $currentPct% → ${skill.label}"
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // If we healed this tick, immediately return to HP monitoring on heal cadence
                    // (preempts Priority 3 Buffs & Priority 4 Combat until HP recovers!)
                    if (healedThisTick) {
                        delay(healRule.healCadenceMs.coerceIn(80L, 1200L))
                        continue
                    }

                    // Track safe HP stability window before allowing a buff cast
                    val isPrimarySafe = !healRule.enabled || primaryHp == null || primaryHp >= healRule.safeBuffMinHpPct
                    if (isPrimarySafe) {
                        consecutiveSafeHpTicks = (consecutiveSafeHpTicks + 1).coerceAtMost(20)
                    } else {
                        consecutiveSafeHpTicks = 0
                    }

                    // 4. PRIORITY 3: ONE-AT-A-TIME SAFE TIMED BUFF REFRESH
                    val safeForBuff = consecutiveSafeHpTicks >= healRule.postHealStabilizeChecks.coerceAtLeast(1)
                    if (now - lastBuffScanMs >= profile.buffScanIntervalMs.coerceIn(500L, 4000L)) {
                        lastBuffScanMs = now
                        scanActiveBuffs(frame, profile, activeLayoutCal, currentLayout, buffStatusMap)
                    }

                    val nextUrgentBuff = selectNextSingleBuffToCast(profile, buffStatusMap, safeForBuff, now)
                    if (nextUrgentBuff != null) {
                        val (link, status, skill) = nextUrgentBuff
                        dispatchSkillKey(skill)
                        lastBuffCastMs[link.id] = now
                        timerOcrReader.clearCache(link.activeBuffId)
                        val timerLabel = if (!status.detected) "MISSING" else BuffTimerOcrReader.formatSecondsShort(status.remainingSeconds)
                        _state.update {
                            it.copy(
                                phase = SmartEnginePhase.BUFFING,
                                nextBuffSummary = "${skill.label} ($timerLabel)",
                                buffStatuses = buffStatusMap.toMap(),
                                lastTelemetry = "BUFF ${skill.label} [$timerLabel] (${skill.shortBadge()})"
                            )
                        }
                        repository.appendLog(
                            profile.id,
                            profile.name,
                            "Buff Refresh",
                            "Cast ${skill.label} (${skill.shortBadge()}) — state: $timerLabel",
                            LogLevel.SUCCESS
                        )
                        // Interruptible cast-guard: keeps checking HP every 140ms so damage immediately preempts!
                        interruptibleCastGuard(profile, activeLayoutCal, link.castGuardMs.coerceIn(200L, 2500L))
                        continue
                    }

                    // 5. PRIORITY 4: COMBAT, ATTACK TOGGLE & TIMED COOLDOWN SKILLS
                    val combatCfg = profile.combatConfig
                    var toggleMatched = false
                    if (combatCfg.enabled && combatCfg.useScreenToggleMatch) {
                        val toggleTplId = activeLayoutCal.attackToggleTemplateId
                        val toggleRect = activeLayoutCal.attackToggleRegion?.toAndroidRect(frame.width, frame.height)
                        if (!toggleTplId.isNullOrBlank()) {
                            val tpl = repository.loadTemplateBitmap(toggleTplId)
                            if (tpl != null) {
                                val res = screenDetector.matchTemplate(
                                    frame,
                                    tpl,
                                    toggleRect,
                                    combatCfg.toggleMinConfidencePct
                                )
                                tpl.recycle()
                                toggleMatched = res.matched
                            }
                        }
                    }

                    val combatActive = combatCfg.enabled && (manualCombatOverride || toggleMatched)
                    var actedInCombat = false
                    if (combatCfg.enabled) {
                        // Timed cooldown skills
                        for (cd in combatCfg.cooldownSkills) {
                            if (!cd.enabled) continue
                            if (cd.onlyWhenCombatActive && !combatActive) continue
                            if (cd.onlyWhenSafeHp && !safeForBuff) continue
                            val lastUsed = lastCooldownSkillMs[cd.id] ?: 0L
                            if (now - lastUsed >= cd.cooldownMs.coerceAtLeast(300L)) {
                                val skill = profile.skillById(cd.skillKeyId)
                                if (skill != null) {
                                    dispatchSkillKey(skill)
                                    lastCooldownSkillMs[cd.id] = now
                                    actedInCombat = true
                                    _state.update {
                                        it.copy(
                                            phase = SmartEnginePhase.COMBAT,
                                            screenToggleMatched = toggleMatched,
                                            lastTelemetry = "CD Skill: ${skill.label} (${skill.shortBadge()})"
                                        )
                                    }
                                    break
                                }
                            }
                        }

                        // Primary attack / AoE keys
                        if (!actedInCombat && combatActive && combatCfg.attackSkillKeyIds.isNotEmpty() &&
                            now - lastAttackMs >= combatCfg.attackIntervalMs.coerceAtLeast(120L)
                        ) {
                            val keyId = combatCfg.attackSkillKeyIds[attackKeyRoundRobin % combatCfg.attackSkillKeyIds.size]
                            attackKeyRoundRobin++
                            val atkSkill = profile.skillById(keyId)
                            if (atkSkill != null) {
                                dispatchSkillKey(atkSkill)
                                lastAttackMs = now
                                actedInCombat = true
                                _state.update {
                                    it.copy(
                                        phase = SmartEnginePhase.COMBAT,
                                        screenToggleMatched = toggleMatched,
                                        lastTelemetry = "ATK ${atkSkill.label} (${atkSkill.shortBadge()})"
                                    )
                                }
                            }
                        }
                    }

                    if (!firedPot && !actedInCombat) {
                        val soonest = buffStatusMap.values
                            .filter { it.remainingSeconds != null }
                            .minByOrNull { it.remainingSeconds ?: Int.MAX_VALUE }
                        val nextTxt = soonest?.let {
                            "${it.buffName} ${BuffTimerOcrReader.formatSecondsShort(it.remainingSeconds)}"
                        } ?: ""
                        _state.update {
                            it.copy(
                                phase = SmartEnginePhase.SAFE_WATCH,
                                screenToggleMatched = toggleMatched,
                                nextBuffSummary = nextTxt,
                                buffStatuses = buffStatusMap.toMap(),
                                lastTelemetry = buildSafeWatchLine(currentLayout, a1Hp, a1Mp, a2Hp, a2Mp, nextTxt)
                            )
                        }
                    }
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                }

                delay(profile.pollingIntervalMs.coerceIn(90L, 500L))
            }
        } catch (_: CancellationException) {
            // Normal stop
        } catch (t: Throwable) {
            val msg = t.message ?: "Smart Flyff Engine error"
            _state.update {
                it.copy(
                    status = ExecutionStatus.ERROR,
                    phase = SmartEnginePhase.IDLE,
                    errorMessage = msg,
                    lastTelemetry = msg
                )
            }
        }
    }

    private suspend fun scanActiveBuffs(
        frame: Bitmap,
        profile: FlyffProfile,
        layoutCal: LayoutHudCalibration,
        currentLayout: DualLayoutMode,
        outStatuses: MutableMap<String, BuffRuntimeStatus>
    ) {
        for (link in profile.buffLinks) {
            if (!link.enabled) continue
            val buffDef = profile.buffById(link.activeBuffId) ?: continue
            val hud = layoutCal.charHuds[buffDef.targetChar] ?: continue
            if (!hud.buffGridRegion.isValid) continue

            val templateId = buffDef.templateIdForLayout(currentLayout) ?: continue
            val tpl = repository.loadTemplateBitmap(templateId) ?: continue
            try {
                val rawRoi = hud.buffGridRegion.toAndroidRect(frame.width, frame.height)
                // Expand search region slightly so minor vertical/horizontal UI shifts never break buff matching
                val padX = (hud.estimatedTileWidth / 3).coerceIn(8, 24)
                val padY = (hud.estimatedTileHeight / 2).coerceIn(12, 36)
                val roi = android.graphics.Rect(
                    (rawRoi.left - padX).coerceAtLeast(0),
                    (rawRoi.top - padY).coerceAtLeast(0),
                    (rawRoi.right + padX).coerceAtMost(frame.width),
                    (rawRoi.bottom + padY).coerceAtMost(frame.height)
                )
                val match = screenDetector.matchTemplate(frame, tpl, roi, link.minConfidencePct)
                if (!match.matched) {
                    timerOcrReader.clearCache(buffDef.id)
                    outStatuses[link.id] = BuffRuntimeStatus(
                        linkId = link.id,
                        buffName = buffDef.label,
                        targetChar = buffDef.targetChar,
                        detected = false,
                        remainingSeconds = 0,
                        needsRefresh = true
                    )
                } else {
                    val strip = FlyffVisionAnalyzer.cropTimerStripFromMatch(
                        frame = frame,
                        matchCenterX = match.centerX,
                        matchCenterY = match.centerY,
                        artWidth = tpl.width,
                        artHeight = tpl.height,
                        fullTileHeightHint = hud.estimatedTileHeight
                    )
                    val reading = if (strip != null) {
                        try {
                            timerOcrReader.readTimerFromStrip(strip, cacheKey = buffDef.id)
                        } finally {
                            if (!strip.isRecycled) strip.recycle()
                        }
                    } else {
                        BuffTimerReading("", null)
                    }
                    val secs = reading.remainingSeconds
                    val needsRefresh = secs != null && secs <= link.refreshBelowSec
                    outStatuses[link.id] = BuffRuntimeStatus(
                        linkId = link.id,
                        buffName = buffDef.label,
                        targetChar = buffDef.targetChar,
                        detected = true,
                        remainingSeconds = secs,
                        needsRefresh = needsRefresh
                    )
                }
            } finally {
                if (!tpl.isRecycled) tpl.recycle()
            }
        }
    }

    private fun selectNextSingleBuffToCast(
        profile: FlyffProfile,
        statuses: Map<String, BuffRuntimeStatus>,
        safeForBuff: Boolean,
        nowMs: Long
    ): Triple<BuffKeyLink, BuffRuntimeStatus, ScannedSkillKey>? {
        val candidates = mutableListOf<Triple<BuffKeyLink, BuffRuntimeStatus, ScannedSkillKey>>()
        for (link in profile.buffLinks) {
            if (!link.enabled) continue
            if (link.requiresSafeHp && !safeForBuff) continue
            val lastCast = lastBuffCastMs[link.id] ?: 0L
            if (nowMs - lastCast < BUFF_RECAST_DEBOUNCE_MS) continue
            val status = statuses[link.id] ?: continue
            if (!status.needsRefresh) continue
            val skill = profile.skillById(link.skillKeyId) ?: continue
            candidates.add(Triple(link, status, skill))
        }
        if (candidates.isEmpty()) return null

        // Missing buffs ALWAYS before expiring buffs; then lowest remaining seconds; then priority
        return candidates.sortedWith(
            compareBy<Triple<BuffKeyLink, BuffRuntimeStatus, ScannedSkillKey>>(
                { it.second.detected }, // false (missing) first
                { it.second.remainingSeconds ?: Int.MAX_VALUE },
                { it.first.priority }
            )
        ).first()
    }

    /**
     * Waits up to `guardMs` after casting a single buff while still checking primary HP every 140ms
     * so if the fighter suddenly takes damage mid-pull, healing interrupts immediately!
     */
    private suspend fun interruptibleCastGuard(
        profile: FlyffProfile,
        layoutCal: LayoutHudCalibration,
        guardMs: Long
    ) {
        val healRule = profile.healRule
        val hpBar = if (healRule.enabled) layoutCal.charHuds[healRule.targetChar]?.hpBar else null
        val deadline = System.currentTimeMillis() + guardMs
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            delay(remaining.coerceAtMost(140L).coerceAtLeast(30L))
            if (hpBar != null) {
                val frame = screenDetector.captureLatestBitmap()
                if (frame != null) {
                    val hp = try {
                        FlyffVisionAnalyzer.measureSingleBarDynamic(frame, hpBar)
                    } finally {
                        if (!frame.isRecycled) frame.recycle()
                    }
                    if (hp < healRule.triggerBelowPct) {
                        consecutiveSafeHpTicks = 0
                        break
                    }
                }
            }
        }
    }

    private suspend fun dispatchSkillKey(skill: ScannedSkillKey) {
        val page = skill.barPage
        if (page != null && page in 1..9 && lastPageByAccount[skill.owner] != page) {
            dualWebInputBridge.dispatchKey(
                keyCode = ScannedSkillKey.keyCodeForFPage(page),
                holdMs = 45L,
                metaState = 0,
                targetOverride = skill.owner
            )
            lastPageByAccount[skill.owner] = page
            delay(65L)
        }
        dualWebInputBridge.dispatchKey(
            keyCode = skill.keyCode,
            holdMs = 80L,
            metaState = skill.metaState,
            targetOverride = skill.owner
        )
    }

    private fun buildSafeWatchLine(
        layout: DualLayoutMode,
        a1Hp: Int?,
        a1Mp: Int?,
        a2Hp: Int?,
        a2Mp: Int?,
        nextBuff: String
    ): String = buildString {
        append(layout.shortLabel)
        if (a1Hp != null) {
            append(" · A1 H:$a1Hp%")
            if (a1Mp != null) append(" M:$a1Mp%")
        }
        if (a2Hp != null) {
            append(" · A2 H:$a2Hp%")
            if (a2Mp != null) append(" M:$a2Mp%")
        }
        if (nextBuff.isNotBlank()) append(" · $nextBuff")
    }

    companion object {
        private const val BUFF_RECAST_DEBOUNCE_MS = 4500L
    }
}

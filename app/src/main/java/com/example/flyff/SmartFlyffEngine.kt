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
    BUFF_SWEEP("Initial Buffs"),
    BUFFING("Safe Buff"),
    COMBAT("Combat"),
    CHAT_PAUSED("Chat · RM Active"),
    DEAD_PAUSED("Dead · Paused"),
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
    private var buffScanJob: Job? = null

    @Volatile
    private var activeProfile: FlyffProfile? = null

    @Volatile
    private var manualCombatOverride: Boolean = false

    @Volatile
    private var latestLayoutSnapshot: Pair<DualLayoutMode, LayoutHudCalibration>? = null

    private val _state = MutableStateFlow(SmartFlyffRuntimeState())
    val state: StateFlow<SmartFlyffRuntimeState> = _state.asStateFlow()

    private val lastPageByAccount = ConcurrentHashMap<WebViewTarget, Int>()
    private val lastVitalTriggerMs = ConcurrentHashMap<String, Long>()
    private val lastBuffCastMs = ConcurrentHashMap<String, Long>()
    private val lastCooldownSkillMs = ConcurrentHashMap<String, Long>()
    private val liveBuffStatuses = ConcurrentHashMap<String, BuffRuntimeStatus>()
    private val consecutiveMissingScans = ConcurrentHashMap<String, Int>()
    private val consecutiveLowTimerScans = ConcurrentHashMap<String, Int>()
    private val lastHealthyTimerMs = ConcurrentHashMap<String, Long>()
    private val initialSweepAttemptCount = ConcurrentHashMap<String, Int>()
    private var lastPrimaryHealMs: Long = 0L
    private var lastSecondaryHealMs: Long = 0L
    private var buffCastGuardUntilMs: Long = 0L
    private var lastAttackMs: Long = 0L
    private var consecutiveSafeHpTicks: Int = 0
    private var attackKeyRoundRobin: Int = 0
    private val liveHudOverrides = ConcurrentHashMap<Pair<DualLayoutMode, WebViewTarget>, CharacterHudRegions>()

    @Volatile
    private var initialBuffSweepActive: Boolean = false

    @Volatile
    private var initialSweepScanCompleted: Boolean = false

    @Volatile
    private var buffReplacementPausedUntilMs: Long = 0L

    @Volatile
    private var mainCharDead: Boolean = false

    @Volatile
    private var a1ChatPaused: Boolean = false

    private var consecutiveZeroHpTicks: Int = 0
    private var consecutiveAliveHpTicks: Int = 0

    fun start(profile: FlyffProfile) {
        stop()
        val effectiveProfile = profile.migrateIfNeeded()
        if (!dualWebInputBridge.isRoutingActive()) {
            val msg = "Open Dual Flyff first to run Smart Profile."
            _state.value = SmartFlyffRuntimeState(
                status = ExecutionStatus.ERROR,
                activeProfileId = effectiveProfile.id,
                activeProfileName = effectiveProfile.name,
                errorMessage = msg,
                lastTelemetry = msg
            )
            return
        }
        if (!screenDetector.isActive.value) {
            val msg = "Grant Screen Capture first so Smart Flyff can read HP/MP/FP bars and buff timers."
            _state.value = SmartFlyffRuntimeState(
                status = ExecutionStatus.ERROR,
                activeProfileId = effectiveProfile.id,
                activeProfileName = effectiveProfile.name,
                errorMessage = msg,
                lastTelemetry = msg
            )
            return
        }

        activeProfile = effectiveProfile
        manualCombatOverride = effectiveProfile.combatConfig.manualCombatEnabled
        latestLayoutSnapshot = null
        lastPageByAccount.clear()
        lastVitalTriggerMs.clear()
        lastBuffCastMs.clear()
        lastCooldownSkillMs.clear()
        liveBuffStatuses.clear()
        consecutiveMissingScans.clear()
        consecutiveLowTimerScans.clear()
        lastHealthyTimerMs.clear()
        initialSweepAttemptCount.clear()
        timerOcrReader.clearCache()
        lastPrimaryHealMs = 0L
        lastSecondaryHealMs = 0L
        buffCastGuardUntilMs = 0L
        lastAttackMs = 0L
        consecutiveSafeHpTicks = 0
        attackKeyRoundRobin = 0
        liveHudOverrides.clear()
        initialBuffSweepActive = effectiveProfile.buffLinks.any { it.enabled }
        initialSweepScanCompleted = false
        buffReplacementPausedUntilMs = 0L
        mainCharDead = false
        a1ChatPaused = false
        consecutiveZeroHpTicks = 0
        consecutiveAliveHpTicks = 0

        _state.value = SmartFlyffRuntimeState(
            status = ExecutionStatus.RUNNING,
            activeProfileId = effectiveProfile.id,
            activeProfileName = effectiveProfile.name,
            activeLayoutMode = dualWebInputBridge.currentLayoutMode(),
            phase = if (initialBuffSweepActive) SmartEnginePhase.BUFF_SWEEP else SmartEnginePhase.SAFE_WATCH,
            manualCombatEnabled = manualCombatOverride,
            lastTelemetry = if (initialBuffSweepActive) {
                "Scanning active buffs (${effectiveProfile.name})..."
            } else {
                "Smart Flyff running (${effectiveProfile.name})"
            }
        )

        runJob = scope.launch {
            repository.appendLog(
                effectiveProfile.id,
                effectiveProfile.name,
                "SmartFlyff",
                "Started Smart Profile (${effectiveProfile.skillKeys.size} keys, ${effectiveProfile.buffLinks.size} buff links, min recast ${effectiveProfile.minBuffRecastSec / 60}m)",
                LogLevel.INFO
            )
            executeLoop()
        }

        // Dedicated background buff/OCR scanner — completely decoupled so HP/Pot/Attack NEVER waits!
        if (effectiveProfile.buffLinks.any { it.enabled }) {
            buffScanJob = scope.launch {
                executeBuffScanLoop()
            }
        }
    }

    fun updateActiveProfile(profile: FlyffProfile) {
        if (activeProfile?.id == profile.id) {
            activeProfile = profile.migrateIfNeeded()
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
        buffScanJob?.cancel()
        buffScanJob = null
        activeProfile = null
        latestLayoutSnapshot = null
        initialBuffSweepActive = false
        initialSweepScanCompleted = false
        buffReplacementPausedUntilMs = 0L
        mainCharDead = false
        a1ChatPaused = false
        _state.update {
            it.copy(
                status = ExecutionStatus.IDLE,
                phase = SmartEnginePhase.IDLE,
                lastTelemetry = "Stopped"
            )
        }
    }

    private suspend fun executeLoop() {
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
                    delay(profile.pollingIntervalMs.coerceIn(25L, 120L))
                    continue
                }

                try {
                    val now = System.currentTimeMillis()
                    val (liveA1Pane, liveA2Pane) = dualWebInputBridge.currentPaneRects()
                    val calibratedLayout = profile.layouts[currentLayout]

                    fun resolvePaneRect(target: WebViewTarget, livePane: android.graphics.Rect): android.graphics.Rect {
                        if (livePane.width() > 80 && livePane.height() > 80) return livePane
                        return when (currentLayout) {
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

                    val a1EffectivePane = resolvePaneRect(WebViewTarget.ACCOUNT_1, liveA1Pane)
                    val a2EffectivePane = resolvePaneRect(WebViewTarget.ACCOUNT_2, liveA2Pane)

                    // Detect Account 1 chat/typing (via Android IME / Quick Type OR visual Flyff Chat overlay)
                    // When open, stop sending keys ONLY to Char 1 (Account 1) while RM (Account 2) continues!
                    val a1VisibleInLayout = currentLayout != DualLayoutMode.SINGLE_ACCT_2
                    val visualChatA1 = a1VisibleInLayout &&
                        FlyffVisionAnalyzer.detectFlyffChatOpenInPane(frame, a1EffectivePane)
                    a1ChatPaused = dualWebInputBridge.isAccount1TypingActive() || visualChatA1

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

                        val autoDetected = FlyffVisionAnalyzer.autoDetectMobileHudBars(frame, paneRect, target)
                        liveHudOverrides[cacheKey] = autoDetected
                        return autoDetected
                    }

                    val a1BaseHud = resolveLiveHud(WebViewTarget.ACCOUNT_1, a1EffectivePane)
                    val a2BaseHud = resolveLiveHud(WebViewTarget.ACCOUNT_2, a2EffectivePane)

                    // 1. Dynamically read HP / MP / FP % with automatic drift re-anchoring (< 1ms fast path)
                    // Skip dynamic re-anchor on A1 while A1 chat overlay is dimming/covering A1's bars
                    val a1Measure = a1BaseHud?.let { hud ->
                        if (a1ChatPaused) {
                            FlyffVisionAnalyzer.LiveHudVitalsMeasurement(
                                hpPct = hud.hpBar?.let { FlyffVisionAnalyzer.measureBarFillPercent(frame, it) },
                                mpPct = hud.mpBar?.let { FlyffVisionAnalyzer.measureBarFillPercent(frame, it) },
                                fpPct = hud.fpBar?.let { FlyffVisionAnalyzer.measureBarFillPercent(frame, it) },
                                activeHud = hud,
                                reanchored = false
                            )
                        } else {
                            FlyffVisionAnalyzer.measureHudVitalsDynamic(frame, hud, liveA1Pane.takeIf { r -> r.width() > 80 })
                        }
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
                    latestLayoutSnapshot = currentLayout to activeLayoutCal

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

                    // 1b. DEATH & REVIVE DETECTION (Main Char)
                    // When dead: STOP ALL KEYS immediately (both Fighter & RM).
                    // When revived: trigger a complete initial buff sweep just like pressing Play!
                    val mainTarget = profile.healRule.targetChar
                    val mainPaneForDeath = if (mainTarget == WebViewTarget.ACCOUNT_1) a1EffectivePane else a2EffectivePane
                    val mainVisible = (mainTarget == WebViewTarget.ACCOUNT_1 && a1Hud != null) ||
                        (mainTarget == WebViewTarget.ACCOUNT_2 && a2Hud != null)
                    val mainPausedForChat = mainTarget == WebViewTarget.ACCOUNT_1 && a1ChatPaused
                    val resDialogVisible = mainVisible && !mainPausedForChat &&
                        FlyffVisionAnalyzer.detectFlyffResurrectionDialog(frame, mainPaneForDeath)
                    val mainHp = vitalPctFor(mainTarget, VitalType.HP)
                    val mainMp = vitalPctFor(mainTarget, VitalType.MP)
                    val mainFp = vitalPctFor(mainTarget, VitalType.FP)
                    val anchoredZeroHp = mainVisible && !mainPausedForChat &&
                        mainHp == 0 && ((mainMp ?: 0) >= 5 || (mainFp ?: 0) >= 5)

                    if (!mainCharDead) {
                        if (anchoredZeroHp) {
                            consecutiveZeroHpTicks++
                        } else {
                            consecutiveZeroHpTicks = 0
                        }
                        if (resDialogVisible || consecutiveZeroHpTicks >= 4) {
                            mainCharDead = true
                            consecutiveAliveHpTicks = 0
                            consecutiveSafeHpTicks = 0
                            _state.update {
                                it.copy(
                                    phase = SmartEnginePhase.DEAD_PAUSED,
                                    lastTelemetry = "DEAD — All keys stopped (waiting for revive)"
                                )
                            }
                            repository.appendLog(
                                profile.id,
                                profile.name,
                                "Death Pause",
                                "Main character died — stopped all keys until revived",
                                LogLevel.WARN
                            )
                        }
                    } else {
                        val revivedSignal = !resDialogVisible && mainHp != null && mainHp >= 15
                        if (revivedSignal) {
                            consecutiveAliveHpTicks++
                        } else {
                            consecutiveAliveHpTicks = 0
                        }
                        if (consecutiveAliveHpTicks >= 2) {
                            mainCharDead = false
                            consecutiveZeroHpTicks = 0
                            consecutiveAliveHpTicks = 0
                            resetBuffStateForFullSweep(profile)
                            _state.update {
                                it.copy(
                                    phase = if (initialBuffSweepActive) SmartEnginePhase.BUFF_SWEEP else SmartEnginePhase.SAFE_WATCH,
                                    lastTelemetry = if (initialBuffSweepActive) {
                                        "REVIVED — Casting all missing buffs..."
                                    } else {
                                        "REVIVED — Resuming macro"
                                    }
                                )
                            }
                            repository.appendLog(
                                profile.id,
                                profile.name,
                                "Revive Rebuff",
                                "Character revived — triggered full active-buff refresh sweep",
                                LogLevel.SUCCESS
                            )
                        }
                    }

                    if (mainCharDead) {
                        _state.update {
                            it.copy(
                                phase = SmartEnginePhase.DEAD_PAUSED,
                                lastTelemetry = "DEAD — All keys stopped (waiting for revive)"
                            )
                        }
                        delay(profile.pollingIntervalMs.coerceIn(35L, 250L))
                        continue
                    }

                    // 2. PRIORITY 1: IMMEDIATE HEALING (zero post-press blocking wait!)
                    var healedThisTick = false
                    val healRule = profile.healRule
                    val effectiveHealCadenceMs = healRule.healCadenceMs.coerceIn(30L, 1200L)
                    val primaryHp = vitalPctFor(healRule.targetChar, VitalType.HP)
                    // Avoid false-healing at 0% if A1's HP bar is covered/dimmed by A1's own Chat overlay;
                    // if A1 HP is genuinely low and readable while chatting, RM still heals!
                    val primaryNeedsHeal = healRule.enabled &&
                        primaryHp != null &&
                        primaryHp < healRule.triggerBelowPct &&
                        !(a1ChatPaused && healRule.targetChar == WebViewTarget.ACCOUNT_1 && primaryHp == 0)
                    if (primaryNeedsHeal) {
                        consecutiveSafeHpTicks = 0
                        if (now - lastPrimaryHealMs >= effectiveHealCadenceMs) {
                            val healSkill = profile.skillById(healRule.healSkillKeyId)
                                ?: profile.skillKeys.firstOrNull { it.category == SkillCategory.HEAL }
                            if (healSkill != null && dispatchSkillKey(healSkill)) {
                                lastPrimaryHealMs = now
                                healedThisTick = true
                                _state.update {
                                    it.copy(
                                        phase = SmartEnginePhase.HEALING,
                                        lastTelemetry = "HEAL ${healRule.targetChar.shortLabel} ($primaryHp% < ${healRule.triggerBelowPct}%)"
                                    )
                                }
                            }
                        }
                    }

                    // Optional secondary heal (e.g. RM self-heal in split view)
                    val secondaryHp = vitalPctFor(healRule.secondaryTargetChar, VitalType.HP)
                    val secondaryNeedsHeal = healRule.secondaryEnabled && secondaryHp != null && secondaryHp < healRule.secondaryTriggerBelowPct
                    if (!primaryNeedsHeal && secondaryNeedsHeal && now - lastSecondaryHealMs >= effectiveHealCadenceMs) {
                        val secSkill = profile.skillById(healRule.secondaryHealSkillKeyId)
                            ?: profile.skillKeys.firstOrNull { it.category == SkillCategory.HEAL && it.owner == healRule.secondaryTargetChar }
                            ?: profile.skillKeys.firstOrNull { it.category == SkillCategory.HEAL }
                        if (secSkill != null && dispatchSkillKey(secSkill)) {
                            lastSecondaryHealMs = now
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
                    // Always evaluated immediately on the exact same tick (never starved by Heal!)
                    // Skips Account 1 pots while Char 1 is chatting; RM (Account 2) pots continue normally!
                    var firedPot = false
                    for (rule in profile.vitalTriggers) {
                        if (!rule.enabled) continue
                        val skill = profile.skillById(rule.skillKeyId) ?: continue
                        if (a1ChatPaused && skill.owner == WebViewTarget.ACCOUNT_1) continue
                        val currentPct = vitalPctFor(rule.targetChar, rule.vitalType) ?: continue
                        if (currentPct < rule.triggerBelowPct) {
                            val lastFired = lastVitalTriggerMs[rule.id] ?: 0L
                            if (now - lastFired >= rule.cooldownMs.coerceAtLeast(100L)) {
                                if (dispatchSkillKey(skill)) {
                                    lastVitalTriggerMs[rule.id] = now
                                    firedPot = true
                                    _state.update {
                                        it.copy(
                                            phase = if (healedThisTick || primaryNeedsHeal) SmartEnginePhase.HEALING else SmartEnginePhase.POTION,
                                            lastTelemetry = "${rule.targetChar.shortLabel} ${rule.vitalType.shortLabel} $currentPct% → ${skill.label}"
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Track safe HP stability window before allowing a buff cast
                    val isPrimarySafe = !healRule.enabled ||
                        primaryHp == null ||
                        (a1ChatPaused && healRule.targetChar == WebViewTarget.ACCOUNT_1) ||
                        primaryHp >= healRule.safeBuffMinHpPct
                    if (isPrimarySafe) {
                        consecutiveSafeHpTicks = (consecutiveSafeHpTicks + 1).coerceAtMost(20)
                    } else {
                        consecutiveSafeHpTicks = 0
                    }

                    // 4. PRIORITY 3: STARTUP / REVIVE FULL BUFF SWEEP -> 8-MIN WAIT -> TIMED REPLACEMENT
                    val safeForBuff = !primaryNeedsHeal &&
                        !secondaryNeedsHeal &&
                        consecutiveSafeHpTicks >= healRule.postHealStabilizeChecks.coerceAtLeast(1)
                    var castBuffThisTick = false

                    if (initialBuffSweepActive && initialSweepScanCompleted) {
                        val scannableLinks = profile.buffLinks.filter { link ->
                            if (!link.enabled) return@filter false
                            val def = profile.buffById(link.activeBuffId) ?: return@filter false
                            val hud = activeLayoutCal.charHuds[def.targetChar] ?: return@filter false
                            hud.buffGridRegion.isValid &&
                                def.templateIdForLayout(currentLayout) != null &&
                                profile.skillById(link.skillKeyId) != null
                        }
                        val mainScannableLinks = scannableLinks.filter { link ->
                            profile.buffById(link.activeBuffId)?.targetChar == mainTarget
                        }
                        val sweepTargetLinks = mainScannableLinks.ifEmpty { scannableLinks }

                        if (sweepTargetLinks.isEmpty()) {
                            completeInitialBuffSweep(profile, now, castCount = 0)
                        } else {
                            val nextSweepBuff = selectNextInitialSweepBuff(
                                profile = profile,
                                scannableLinks = scannableLinks,
                                mainTarget = mainTarget,
                                statuses = liveBuffStatuses,
                                safeForBuff = safeForBuff,
                                nowMs = now
                            )
                            if (nextSweepBuff != null && now >= buffCastGuardUntilMs) {
                                val (link, status, skill) = nextSweepBuff
                                if (dispatchSkillKey(skill)) {
                                    lastBuffCastMs[link.id] = now
                                    initialSweepAttemptCount[link.id] = (initialSweepAttemptCount[link.id] ?: 0) + 1
                                    consecutiveMissingScans[link.id] = 0
                                    consecutiveLowTimerScans[link.id] = 0
                                    timerOcrReader.clearCache(link.activeBuffId)
                                    buffCastGuardUntilMs = now + link.castGuardMs.coerceIn(120L, 1200L)
                                    castBuffThisTick = true
                                    val doneCount = sweepTargetLinks.count { l ->
                                        liveBuffStatuses[l.id]?.detected == true || (initialSweepAttemptCount[l.id] ?: 0) >= 1
                                    }
                                    _state.update {
                                        it.copy(
                                            phase = SmartEnginePhase.BUFF_SWEEP,
                                            nextBuffSummary = "Init ${skill.label} ($doneCount/${sweepTargetLinks.size})",
                                            buffStatuses = liveBuffStatuses.toMap(),
                                            lastTelemetry = "INIT BUFF ${skill.label} ($doneCount/${sweepTargetLinks.size})"
                                        )
                                    }
                                    repository.appendLog(
                                        profile.id,
                                        profile.name,
                                        "Initial Buff Sweep",
                                        "Cast missing buff ${skill.label} (${skill.shortBadge()}) — $doneCount/${sweepTargetLinks.size}",
                                        LogLevel.SUCCESS
                                    )
                                }
                            } else {
                                // Check if all main-char buffs are now confirmed active OR all missing buffs
                                // have completed their sweep attempts (wait until every buff is active or retried)
                                val allConfirmedUp = sweepTargetLinks.all { l ->
                                    liveBuffStatuses[l.id]?.detected == true
                                }
                                val allAttemptedAndSettled = sweepTargetLinks.all { l ->
                                    liveBuffStatuses[l.id]?.detected == true ||
                                        (initialSweepAttemptCount[l.id] ?: 0) >= MAX_INITIAL_SWEEP_ATTEMPTS
                                }
                                if (allConfirmedUp || allAttemptedAndSettled) {
                                    val totalCasts = initialSweepAttemptCount.values.sum()
                                    completeInitialBuffSweep(profile, now, castCount = totalCasts)
                                }
                            }
                        }
                    } else if (!initialBuffSweepActive && now >= buffReplacementPausedUntilMs && safeForBuff && now >= buffCastGuardUntilMs) {
                        val nextUrgentBuff = selectNextSingleBuffToCast(profile, liveBuffStatuses, safeForBuff = true, nowMs = now)
                        if (nextUrgentBuff != null) {
                            val (link, status, skill) = nextUrgentBuff
                            if (dispatchSkillKey(skill)) {
                                lastBuffCastMs[link.id] = now
                                consecutiveMissingScans[link.id] = 0
                                consecutiveLowTimerScans[link.id] = 0
                                timerOcrReader.clearCache(link.activeBuffId)
                                buffCastGuardUntilMs = now + link.castGuardMs.coerceIn(60L, 1500L)
                                castBuffThisTick = true
                                liveBuffStatuses[link.id] = status.copy(
                                    detected = true,
                                    needsRefresh = false
                                )
                                val timerLabel = if (!status.detected) "MISSING" else BuffTimerOcrReader.formatSecondsShort(status.remainingSeconds)
                                _state.update {
                                    it.copy(
                                        phase = SmartEnginePhase.BUFFING,
                                        nextBuffSummary = "${skill.label} ($timerLabel)",
                                        buffStatuses = liveBuffStatuses.toMap(),
                                        lastTelemetry = "BUFF ${skill.label} [$timerLabel] (${skill.shortBadge()})"
                                    )
                                }
                                repository.appendLog(
                                    profile.id,
                                    profile.name,
                                    "Buff Refresh",
                                    "Cast ${skill.label} (${skill.shortBadge()}) — state: $timerLabel (min recast ${profile.minBuffRecastSec / 60}m)",
                                    LogLevel.SUCCESS
                                )
                            }
                        }
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

                    val effectiveAttackKeyIds = combatCfg.attackSkillKeyIds.ifEmpty {
                        profile.skillKeys.filter { it.category == SkillCategory.ATTACK_AOE }.map { it.id }
                    }
                    val combatActive = manualCombatOverride || (combatCfg.enabled && toggleMatched)
                    var actedInCombat = false
                    if (combatCfg.enabled || manualCombatOverride) {
                        // Timed cooldown skills
                        for (cd in combatCfg.cooldownSkills) {
                            if (!cd.enabled) continue
                            if (cd.onlyWhenCombatActive && !combatActive) continue
                            if (cd.onlyWhenSafeHp && !safeForBuff) continue
                            val skill = profile.skillById(cd.skillKeyId) ?: continue
                            if (a1ChatPaused && skill.owner == WebViewTarget.ACCOUNT_1) continue
                            val lastUsed = lastCooldownSkillMs[cd.id] ?: 0L
                            if (now - lastUsed >= cd.cooldownMs.coerceAtLeast(300L)) {
                                if (dispatchSkillKey(skill)) {
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

                        // Primary attack / AoE keys — fast multi-hit-per-second cadence (min 35ms, default 80ms)
                        // Runs independently of RM healing (A1 can attack while A2 heals!)
                        if (!actedInCombat && combatActive && effectiveAttackKeyIds.isNotEmpty() &&
                            now - lastAttackMs >= combatCfg.attackIntervalMs.coerceAtLeast(35L)
                        ) {
                            val keyId = effectiveAttackKeyIds[attackKeyRoundRobin % effectiveAttackKeyIds.size]
                            attackKeyRoundRobin++
                            val atkSkill = profile.skillById(keyId)
                            if (atkSkill != null && !(a1ChatPaused && atkSkill.owner == WebViewTarget.ACCOUNT_1)) {
                                if (dispatchSkillKey(atkSkill)) {
                                    lastAttackMs = now
                                    actedInCombat = true
                                    if (!healedThisTick && !firedPot && !castBuffThisTick) {
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
                        }
                    }

                    if (!healedThisTick && !firedPot && !castBuffThisTick && !actedInCombat) {
                        val waitRemainingSec = ((buffReplacementPausedUntilMs - now) / 1000L).coerceAtLeast(0L).toInt()
                        val nextTxt = when {
                            initialBuffSweepActive -> "Initial buff check"
                            waitRemainingSec > 0 -> "Buffs OK (${BuffTimerOcrReader.formatSecondsShort(waitRemainingSec)})"
                            else -> {
                                val soonest = liveBuffStatuses.values
                                    .filter { it.remainingSeconds != null && it.remainingSeconds > 0 }
                                    .minByOrNull { it.remainingSeconds ?: Int.MAX_VALUE }
                                soonest?.let {
                                    "${it.buffName} ${BuffTimerOcrReader.formatSecondsShort(it.remainingSeconds)}"
                                } ?: ""
                            }
                        }
                        val idlePhase = when {
                            a1ChatPaused -> SmartEnginePhase.CHAT_PAUSED
                            initialBuffSweepActive -> SmartEnginePhase.BUFF_SWEEP
                            else -> SmartEnginePhase.SAFE_WATCH
                        }
                        val idleLine = if (a1ChatPaused) {
                            "A1 Chat paused (RM active)"
                        } else {
                            buildSafeWatchLine(currentLayout, a1Hp, a1Mp, a2Hp, a2Mp, nextTxt)
                        }
                        _state.update {
                            it.copy(
                                phase = idlePhase,
                                screenToggleMatched = toggleMatched,
                                nextBuffSummary = nextTxt,
                                buffStatuses = liveBuffStatuses.toMap(),
                                lastTelemetry = idleLine
                            )
                        }
                    }
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                }

                // Lightweight CPU-yield tick only (default 45ms, ZERO post-button stall!)
                delay(profile.pollingIntervalMs.coerceIn(25L, 250L))
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

    private suspend fun resetBuffStateForFullSweep(profile: FlyffProfile) {
        lastBuffCastMs.clear()
        liveBuffStatuses.clear()
        consecutiveMissingScans.clear()
        consecutiveLowTimerScans.clear()
        lastHealthyTimerMs.clear()
        initialSweepAttemptCount.clear()
        timerOcrReader.clearCache()
        buffCastGuardUntilMs = 0L
        buffReplacementPausedUntilMs = 0L
        initialSweepScanCompleted = false
        initialBuffSweepActive = profile.buffLinks.any { it.enabled }
    }

    private suspend fun completeInitialBuffSweep(profile: FlyffProfile, nowMs: Long, castCount: Int) {
        val waitMs = effectiveMinBuffRecastMs(profile)
        initialBuffSweepActive = false
        buffReplacementPausedUntilMs = nowMs + waitMs
        val waitMins = (waitMs / 60_000L).coerceAtLeast(1L)
        repository.appendLog(
            profile.id,
            profile.name,
            "Buff Sweep Done",
            "All active buffs ready ($castCount cast) — waiting ${waitMins}m before replacement cycle",
            LogLevel.INFO
        )
    }

    /**
     * Background buff icon + timer OCR scanner running on its own coroutine so scanning 15–20
     * buffs never delays HP polling, emergency healing, pots, or rapid attacks.
     */
    private suspend fun executeBuffScanLoop() {
        try {
            while (scope.isActive) {
                val profile = activeProfile ?: break
                if (!dualWebInputBridge.isRoutingActive() || !screenDetector.isActive.value) break

                val now = System.currentTimeMillis()
                if (mainCharDead) {
                    delay(250L)
                    continue
                }
                // During the post-sweep 8-minute wait, avoid unnecessary template/OCR churn
                if (!initialBuffSweepActive && now < buffReplacementPausedUntilMs) {
                    delay(500L)
                    continue
                }

                val snapshot = latestLayoutSnapshot
                if (snapshot == null) {
                    delay(50L)
                    continue
                }
                val (currentLayout, layoutCal) = snapshot
                val frame = screenDetector.captureLatestBitmap()
                if (frame != null) {
                    try {
                        scanActiveBuffs(frame, profile, layoutCal, currentLayout, isInitialSweep = initialBuffSweepActive)
                        if (initialBuffSweepActive) {
                            initialSweepScanCompleted = true
                        }
                    } finally {
                        if (!frame.isRecycled) frame.recycle()
                    }
                }
                val scanDelay = if (initialBuffSweepActive) {
                    300L
                } else {
                    profile.buffScanIntervalMs.coerceIn(600L, 5000L)
                }
                delay(scanDelay)
            }
        } catch (_: CancellationException) {
            // Normal stop
        } catch (_: Throwable) {
            // Keep main heal/combat loop unaffected if OCR hits a transient issue
        }
    }

    private suspend fun scanActiveBuffs(
        frame: Bitmap,
        profile: FlyffProfile,
        layoutCal: LayoutHudCalibration,
        currentLayout: DualLayoutMode,
        isInitialSweep: Boolean
    ) {
        val now = System.currentTimeMillis()
        val minRecastMs = effectiveMinBuffRecastMs(profile)

        for (link in profile.buffLinks) {
            if (!scope.isActive) return
            if (!link.enabled) continue
            val buffDef = profile.buffById(link.activeBuffId) ?: continue
            // If A1's chat overlay is covering A1's buff grid, skip scanning A1's buffs until chat closes
            if (a1ChatPaused && buffDef.targetChar == WebViewTarget.ACCOUNT_1) continue
            val hud = layoutCal.charHuds[buffDef.targetChar] ?: continue
            if (!hud.buffGridRegion.isValid) continue

            // Outside initial sweep, skip OCR work for buffs deep inside their 8-min recast lockout
            if (!isInitialSweep) {
                val lastCast = lastBuffCastMs[link.id] ?: 0L
                if (lastCast > 0L && now - lastCast < minRecastMs) {
                    val existing = liveBuffStatuses[link.id]
                    if (existing == null || existing.needsRefresh) {
                        liveBuffStatuses[link.id] = BuffRuntimeStatus(
                            linkId = link.id,
                            buffName = buffDef.label,
                            targetChar = buffDef.targetChar,
                            detected = true,
                            remainingSeconds = existing?.remainingSeconds,
                            needsRefresh = false
                        )
                    }
                    continue
                }
            }

            val templateId = buffDef.templateIdForLayout(currentLayout) ?: continue
            val tpl = repository.loadTemplateBitmap(templateId) ?: continue
            try {
                val rawRoi = hud.buffGridRegion.toAndroidRect(frame.width, frame.height)
                val padX = (hud.estimatedTileWidth / 3).coerceIn(8, 24)
                val padY = (hud.estimatedTileHeight / 2).coerceIn(12, 36)
                val roi = android.graphics.Rect(
                    (rawRoi.left - padX).coerceAtLeast(0),
                    (rawRoi.top - padY).coerceAtLeast(0),
                    (rawRoi.right + padX).coerceAtMost(frame.width),
                    (rawRoi.bottom + padY).coerceAtMost(frame.height)
                )
                val match = screenDetector.matchTemplate(frame, tpl, roi, link.minConfidencePct.coerceIn(60, 95))
                if (!match.matched) {
                    timerOcrReader.clearCache(buffDef.id)
                    val misses = (consecutiveMissingScans[link.id] ?: 0) + 1
                    consecutiveMissingScans[link.id] = misses
                    consecutiveLowTimerScans[link.id] = 0
                    // During initial startup/revive sweep, detect missing buffs immediately (1 scan);
                    // during ongoing play, require 2 consecutive misses to reject 1-frame sparkle flicker
                    val confirmedMissing = if (isInitialSweep) misses >= 1 else misses >= 2
                    liveBuffStatuses[link.id] = BuffRuntimeStatus(
                        linkId = link.id,
                        buffName = buffDef.label,
                        targetChar = buffDef.targetChar,
                        detected = !confirmedMissing,
                        remainingSeconds = if (confirmedMissing) 0 else liveBuffStatuses[link.id]?.remainingSeconds,
                        needsRefresh = confirmedMissing
                    )
                } else {
                    consecutiveMissingScans[link.id] = 0
                    if (isInitialSweep) {
                        // During initial sweep, presence alone confirms the buff is already active!
                        liveBuffStatuses[link.id] = BuffRuntimeStatus(
                            linkId = link.id,
                            buffName = buffDef.label,
                            targetChar = buffDef.targetChar,
                            detected = true,
                            remainingSeconds = liveBuffStatuses[link.id]?.remainingSeconds,
                            needsRefresh = false
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
                        if (secs != null && secs >= 180) {
                            lastHealthyTimerMs[link.id] = now
                        }
                        val rawLow = secs != null && secs <= link.refreshBelowSec
                        val needsRefresh = if (rawLow) {
                            val lowCount = (consecutiveLowTimerScans[link.id] ?: 0) + 1
                            consecutiveLowTimerScans[link.id] = lowCount
                            val recentlyHealthy = (now - (lastHealthyTimerMs[link.id] ?: 0L)) < 90_000L
                            if (recentlyHealthy) lowCount >= 2 else true
                        } else {
                            consecutiveLowTimerScans[link.id] = 0
                            false
                        }
                        liveBuffStatuses[link.id] = BuffRuntimeStatus(
                            linkId = link.id,
                            buffName = buffDef.label,
                            targetChar = buffDef.targetChar,
                            detected = true,
                            remainingSeconds = secs,
                            needsRefresh = needsRefresh
                        )
                    }
                }
            } finally {
                if (!tpl.isRecycled) tpl.recycle()
            }
        }
    }

    private fun effectiveMinBuffRecastMs(profile: FlyffProfile): Long {
        val configuredMs = profile.minBuffRecastSec.coerceIn(0, 3600) * 1000L
        return configuredMs.coerceAtLeast(MIN_BUFF_DEBOUNCE_FLOOR_MS)
    }

    private fun selectNextInitialSweepBuff(
        profile: FlyffProfile,
        scannableLinks: List<BuffKeyLink>,
        mainTarget: WebViewTarget,
        statuses: Map<String, BuffRuntimeStatus>,
        safeForBuff: Boolean,
        nowMs: Long
    ): Triple<BuffKeyLink, BuffRuntimeStatus, ScannedSkillKey>? {
        val candidates = mutableListOf<Triple<BuffKeyLink, BuffRuntimeStatus, ScannedSkillKey>>()
        for (link in scannableLinks) {
            if (link.requiresSafeHp && !safeForBuff) continue
            val status = statuses[link.id] ?: continue
            if (status.detected) continue
            val attempts = initialSweepAttemptCount[link.id] ?: 0
            if (attempts >= MAX_INITIAL_SWEEP_ATTEMPTS) continue
            val lastCast = lastBuffCastMs[link.id] ?: 0L
            if (lastCast > 0L && nowMs - lastCast < INITIAL_SWEEP_RETRY_DELAY_MS) continue
            val skill = profile.skillById(link.skillKeyId) ?: continue
            if (a1ChatPaused && skill.owner == WebViewTarget.ACCOUNT_1) continue
            candidates.add(Triple(link, status, skill))
        }
        if (candidates.isEmpty()) return null
        // Main char buffs first, then unattempted before retries, then link priority
        return candidates.sortedWith(
            compareBy<Triple<BuffKeyLink, BuffRuntimeStatus, ScannedSkillKey>>(
                { profile.buffById(it.first.activeBuffId)?.targetChar != mainTarget },
                { initialSweepAttemptCount[it.first.id] ?: 0 },
                { it.first.priority }
            )
        ).first()
    }

    private fun selectNextSingleBuffToCast(
        profile: FlyffProfile,
        statuses: Map<String, BuffRuntimeStatus>,
        safeForBuff: Boolean,
        nowMs: Long
    ): Triple<BuffKeyLink, BuffRuntimeStatus, ScannedSkillKey>? {
        val minRecastMs = effectiveMinBuffRecastMs(profile)
        val candidates = mutableListOf<Triple<BuffKeyLink, BuffRuntimeStatus, ScannedSkillKey>>()
        for (link in profile.buffLinks) {
            if (!link.enabled) continue
            if (link.requiresSafeHp && !safeForBuff) continue
            val lastCast = lastBuffCastMs[link.id] ?: 0L
            // Enforce minimum buff recast interval (default 8 minutes = 480s) after casting a buff!
            if (lastCast > 0L && nowMs - lastCast < minRecastMs) continue
            val status = statuses[link.id] ?: continue
            if (!status.needsRefresh) continue
            val skill = profile.skillById(link.skillKeyId) ?: continue
            if (a1ChatPaused && skill.owner == WebViewTarget.ACCOUNT_1) continue
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

    private suspend fun dispatchSkillKey(skill: ScannedSkillKey): Boolean {
        if (mainCharDead) return false
        if (a1ChatPaused && skill.owner == WebViewTarget.ACCOUNT_1) return false
        val page = skill.barPage
        if (page != null && page in 1..9 && lastPageByAccount[skill.owner] != page) {
            dualWebInputBridge.dispatchKey(
                keyCode = ScannedSkillKey.keyCodeForFPage(page),
                holdMs = 25L,
                metaState = 0,
                targetOverride = skill.owner
            )
            lastPageByAccount[skill.owner] = page
            delay(25L)
        }
        return dualWebInputBridge.dispatchKey(
            keyCode = skill.keyCode,
            holdMs = 30L,
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
        // Hard anti-double-cast floor even if user sets minBuffRecastSec to 0
        private const val MIN_BUFF_DEBOUNCE_FLOOR_MS = 4500L
        private const val INITIAL_SWEEP_RETRY_DELAY_MS = 2500L
        private const val MAX_INITIAL_SWEEP_ATTEMPTS = 2
    }
}

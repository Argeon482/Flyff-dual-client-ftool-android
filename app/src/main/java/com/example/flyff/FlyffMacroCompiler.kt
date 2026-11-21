package com.example.flyff

import com.example.dualweb.DualLayoutMode
import com.example.model.BranchAction
import com.example.model.CallFunctionStep
import com.example.model.DelayStep
import com.example.model.IfBarPercentStep
import com.example.model.IfBuffTimerStep
import com.example.model.IfImageStep
import com.example.model.IfVariableStep
import com.example.model.KeyPressStep
import com.example.model.MacroDefinition
import com.example.model.MacroFunction
import com.example.model.MacroStep
import com.example.model.SetVariableStep
import com.example.model.VarComparator
import com.example.model.VarOperation
import java.util.UUID

/**
 * Compiles a calibrated FlyffProfile into a clean, modular, editable standard MacroDefinition
 * with functions for Priority Heal, Vital Pots, One-at-a-Time Buff Refresh, and Combat Rotation.
 */
object FlyffMacroCompiler {

    fun compileToMacro(
        profile: FlyffProfile,
        layoutMode: DualLayoutMode = profile.layouts.keys.firstOrNull() ?: DualLayoutMode.SPLIT_50_50
    ): MacroDefinition {
        val layoutCal = profile.layouts[layoutMode]
        val functions = mutableListOf<MacroFunction>()
        val mainSteps = mutableListOf<MacroStep>()

        // 1. Priority Heal function
        val healFnId = UUID.randomUUID().toString()
        val healSteps = buildHealFunctionSteps(profile, layoutCal)
        if (healSteps.isNotEmpty()) {
            functions.add(
                MacroFunction(
                    id = healFnId,
                    name = "priority_heal",
                    description = "Checks HP bar % and heals immediately; sets safe_for_buff flag.",
                    steps = healSteps
                )
            )
            mainSteps.add(
                CallFunctionStep(
                    label = "Call priority_heal",
                    functionId = healFnId,
                    functionName = "priority_heal"
                )
            )
        }

        // 2. HP / MP / FP Pot & Food Triggers function
        val potsFnId = UUID.randomUUID().toString()
        val potSteps = buildVitalPotsFunctionSteps(profile, layoutCal)
        if (potSteps.isNotEmpty()) {
            functions.add(
                MacroFunction(
                    id = potsFnId,
                    name = "vital_pots",
                    description = "Triggers HP, MP, and FP pots/food below configured %.",
                    steps = potSteps
                )
            )
            mainSteps.add(
                CallFunctionStep(
                    label = "Call vital_pots",
                    functionId = potsFnId,
                    functionName = "vital_pots"
                )
            )
        }

        // 3. Safe One-at-a-Time Buff Refresh function
        val buffFnId = UUID.randomUUID().toString()
        val buffSteps = buildSafeBuffFunctionSteps(profile, layoutCal, layoutMode)
        if (buffSteps.isNotEmpty()) {
            functions.add(
                MacroFunction(
                    id = buffFnId,
                    name = "safe_buff_one",
                    description = "Casts at most one missing or low-timer buff when HP is safe.",
                    steps = buffSteps
                )
            )
            mainSteps.add(
                IfVariableStep(
                    label = "Only buff when HP is safe",
                    variableName = "safe_for_buff",
                    comparator = VarComparator.EQUALS,
                    compareValue = "1",
                    thenAction = BranchAction.CONTINUE_NEXT,
                    elseAction = BranchAction.SKIP_NEXT
                )
            )
            mainSteps.add(
                CallFunctionStep(
                    label = "Call safe_buff_one",
                    functionId = buffFnId,
                    functionName = "safe_buff_one"
                )
            )
        }

        // 4. Combat & Attack Toggle function
        val combatFnId = UUID.randomUUID().toString()
        val combatSteps = buildCombatFunctionSteps(profile, layoutCal)
        if (combatSteps.isNotEmpty()) {
            functions.add(
                MacroFunction(
                    id = combatFnId,
                    name = "combat_rotation",
                    description = "Attack / AoE keys gated by toggle match plus timed cooldown skills.",
                    steps = combatSteps
                )
            )
            mainSteps.add(
                CallFunctionStep(
                    label = "Call combat_rotation",
                    functionId = combatFnId,
                    functionName = "combat_rotation"
                )
            )
        }

        if (mainSteps.isEmpty()) {
            mainSteps.add(DelayStep(label = "Idle Wait", minMs = 250L, maxMs = 250L))
        }

        return MacroDefinition(
            id = UUID.randomUUID().toString(),
            name = "${profile.name} (${layoutMode.shortLabel})",
            description = "Exported from Smart Flyff Profile '${profile.name}' for ${layoutMode.label}.",
            targetAppLabel = "Dual Flyff",
            loopCount = 0, // Continuous infinite loop
            pollingIntervalMs = profile.pollingIntervalMs.coerceIn(25L, 250L),
            steps = mainSteps,
            functions = functions
        )
    }

    private fun buildHealFunctionSteps(
        profile: FlyffProfile,
        layoutCal: LayoutHudCalibration?
    ): List<MacroStep> {
        val heal = profile.healRule
        val healKey = profile.skillById(heal.healSkillKeyId)
            ?: profile.skillKeys.firstOrNull { it.category == SkillCategory.HEAL }
            ?: return emptyList()
        val hpBar = layoutCal?.charHuds?.get(heal.targetChar)?.hpBar ?: return emptyList()

        val steps = mutableListOf<MacroStep>()
        steps.add(
            SetVariableStep(
                label = "Default safe_for_buff = 1",
                variableName = "safe_for_buff",
                operation = VarOperation.SET,
                valueExpression = "1"
            )
        )
        val afterHealStepId = UUID.randomUUID().toString()
        steps.add(
            IfBarPercentStep(
                label = "If ${heal.targetChar.shortLabel} HP < ${heal.triggerBelowPct}%",
                vitalLabel = "HP",
                left = hpBar.rect.left,
                top = hpBar.rect.top,
                right = hpBar.rect.right,
                bottom = hpBar.rect.bottom,
                normLeft = hpBar.rect.normLeft,
                normTop = hpBar.rect.normTop,
                normRight = hpBar.rect.normRight,
                normBottom = hpBar.rect.normBottom,
                comparator = VarComparator.LESS_THAN,
                thresholdPct = heal.triggerBelowPct,
                thenAction = BranchAction.CONTINUE_NEXT,
                elseAction = BranchAction.JUMP_TO_STEP,
                elseJumpStepId = afterHealStepId,
                targetWebView = heal.targetChar
            )
        )
        steps.add(
            SetVariableStep(
                label = "Unsafe while healing",
                variableName = "safe_for_buff",
                operation = VarOperation.SET,
                valueExpression = "0"
            )
        )
        steps.addAll(keyStepsForSkill(healKey))
        steps.add(
            DelayStep(
                id = afterHealStepId,
                label = "Heal cadence",
                minMs = heal.healCadenceMs.coerceIn(30L, 1200L),
                maxMs = heal.healCadenceMs.coerceIn(30L, 1200L)
            )
        )
        // Check safe buff threshold separately if different from heal threshold
        if (heal.safeBuffMinHpPct > heal.triggerBelowPct) {
            steps.add(
                IfBarPercentStep(
                    label = "If ${heal.targetChar.shortLabel} HP < ${heal.safeBuffMinHpPct}% (unsafe for buff)",
                    vitalLabel = "HP",
                    left = hpBar.rect.left,
                    top = hpBar.rect.top,
                    right = hpBar.rect.right,
                    bottom = hpBar.rect.bottom,
                    normLeft = hpBar.rect.normLeft,
                    normTop = hpBar.rect.normTop,
                    normRight = hpBar.rect.normRight,
                    normBottom = hpBar.rect.normBottom,
                    comparator = VarComparator.LESS_THAN,
                    thresholdPct = heal.safeBuffMinHpPct,
                    thenAction = BranchAction.CONTINUE_NEXT,
                    elseAction = BranchAction.SKIP_NEXT,
                    targetWebView = heal.targetChar
                )
            )
            steps.add(
                SetVariableStep(
                    label = "Mark unsafe for buff",
                    variableName = "safe_for_buff",
                    operation = VarOperation.SET,
                    valueExpression = "0"
                )
            )
        }
        return steps
    }

    private fun buildVitalPotsFunctionSteps(
        profile: FlyffProfile,
        layoutCal: LayoutHudCalibration?
    ): List<MacroStep> {
        val steps = mutableListOf<MacroStep>()
        for (rule in profile.vitalTriggers.filter { it.enabled }) {
            val skill = profile.skillById(rule.skillKeyId) ?: continue
            val bar = layoutCal?.charHuds?.get(rule.targetChar)?.barFor(rule.vitalType) ?: continue
            val skipId = UUID.randomUUID().toString()
            steps.add(
                IfBarPercentStep(
                    label = "If ${rule.targetChar.shortLabel} ${rule.vitalType.shortLabel} < ${rule.triggerBelowPct}%",
                    vitalLabel = rule.vitalType.shortLabel,
                    left = bar.rect.left,
                    top = bar.rect.top,
                    right = bar.rect.right,
                    bottom = bar.rect.bottom,
                    normLeft = bar.rect.normLeft,
                    normTop = bar.rect.normTop,
                    normRight = bar.rect.normRight,
                    normBottom = bar.rect.normBottom,
                    comparator = VarComparator.LESS_THAN,
                    thresholdPct = rule.triggerBelowPct,
                    thenAction = BranchAction.CONTINUE_NEXT,
                    elseAction = BranchAction.JUMP_TO_STEP,
                    elseJumpStepId = skipId,
                    targetWebView = rule.targetChar
                )
            )
            steps.addAll(keyStepsForSkill(skill))
            steps.add(
                DelayStep(
                    id = skipId,
                    label = "Next vital check",
                    minMs = 0L,
                    maxMs = 0L
                )
            )
        }
        return steps
    }

    private fun buildSafeBuffFunctionSteps(
        profile: FlyffProfile,
        layoutCal: LayoutHudCalibration?,
        layoutMode: DualLayoutMode
    ): List<MacroStep> {
        val steps = mutableListOf<MacroStep>()
        val endStepId = UUID.randomUUID().toString()

        val sortedLinks = profile.buffLinks
            .filter { it.enabled }
            .sortedBy { it.priority }

        for (link in sortedLinks) {
            val skill = profile.skillById(link.skillKeyId) ?: continue
            val buff = profile.buffById(link.activeBuffId) ?: continue
            val hud = layoutCal?.charHuds?.get(buff.targetChar) ?: continue
            val tplId = buff.templateIdForLayout(layoutMode) ?: continue
            val nextBuffCheckId = UUID.randomUUID().toString()

            steps.add(
                IfBuffTimerStep(
                    label = "If ${buff.label} missing or < ${link.refreshBelowSec}s",
                    buffName = buff.label,
                    templateId = tplId,
                    searchLeft = hud.buffGridRegion.left,
                    searchTop = hud.buffGridRegion.top,
                    searchRight = hud.buffGridRegion.right,
                    searchBottom = hud.buffGridRegion.bottom,
                    tileHeightPx = hud.estimatedTileHeight,
                    minConfidencePct = link.minConfidencePct,
                    refreshBelowSec = link.refreshBelowSec,
                    triggerWhenMissing = true,
                    thenAction = BranchAction.CONTINUE_NEXT,
                    elseAction = BranchAction.JUMP_TO_STEP,
                    elseJumpStepId = nextBuffCheckId,
                    targetWebView = buff.targetChar
                )
            )
            steps.addAll(keyStepsForSkill(skill))
            steps.add(
                DelayStep(
                    label = "Cast guard (${skill.label})",
                    minMs = link.castGuardMs.coerceIn(60L, 1500L),
                    maxMs = link.castGuardMs.coerceIn(60L, 1500L)
                )
            )
            // Cast strictly ONE buff per safe pass, then jump out to re-check HP!
            steps.add(
                com.example.model.JumpStep(
                    label = "Return to HP check after 1 buff",
                    targetStepId = endStepId
                )
            )
            steps.add(
                DelayStep(
                    id = nextBuffCheckId,
                    label = "Next buff check",
                    minMs = 0L,
                    maxMs = 0L
                )
            )
        }

        if (steps.isNotEmpty()) {
            steps.add(
                DelayStep(
                    id = endStepId,
                    label = "Buff pass end",
                    minMs = 0L,
                    maxMs = 0L
                )
            )
        }
        return steps
    }

    private fun buildCombatFunctionSteps(
        profile: FlyffProfile,
        layoutCal: LayoutHudCalibration?
    ): List<MacroStep> {
        val combat = profile.combatConfig
        if (!combat.enabled) return emptyList()
        val attackSkills = combat.attackSkillKeyIds.mapNotNull { profile.skillById(it) }
        val cdSkills = combat.cooldownSkills.filter { it.enabled }.mapNotNull { profile.skillById(it.skillKeyId) }
        if (attackSkills.isEmpty() && cdSkills.isEmpty()) return emptyList()

        val steps = mutableListOf<MacroStep>()
        val endStepId = UUID.randomUUID().toString()

        if (combat.useScreenToggleMatch && !layoutCal?.attackToggleTemplateId.isNullOrBlank()) {
            val roi = layoutCal?.attackToggleRegion
            steps.add(
                IfImageStep(
                    label = "If Attack Toggle active",
                    templateId = layoutCal?.attackToggleTemplateId.orEmpty(),
                    templateName = "Attack Toggle",
                    searchLeft = roi?.left ?: 0,
                    searchTop = roi?.top ?: 0,
                    searchRight = roi?.right ?: 0,
                    searchBottom = roi?.bottom ?: 0,
                    minConfidencePct = combat.toggleMinConfidencePct,
                    tapOnMatch = false,
                    thenAction = BranchAction.CONTINUE_NEXT,
                    elseAction = BranchAction.JUMP_TO_STEP,
                    elseJumpStepId = endStepId
                )
            )
        }

        for (skill in attackSkills) {
            steps.addAll(keyStepsForSkill(skill))
        }
        for (skill in cdSkills) {
            steps.addAll(keyStepsForSkill(skill))
        }
        steps.add(
            DelayStep(
                id = endStepId,
                label = "Combat cadence",
                minMs = combat.attackIntervalMs.coerceIn(35L, 5000L),
                maxMs = combat.attackIntervalMs.coerceIn(35L, 5000L)
            )
        )
        return steps
    }

    private fun keyStepsForSkill(skill: ScannedSkillKey): List<MacroStep> {
        val out = mutableListOf<MacroStep>()
        val page = skill.barPage
        if (page != null && page in 1..9) {
            out.add(
                KeyPressStep(
                    label = "Page F$page (${skill.owner.shortLabel})",
                    customKeyCode = ScannedSkillKey.keyCodeForFPage(page),
                    keyLabel = "F$page",
                    holdMs = 25L,
                    metaState = 0,
                    targetWebView = skill.owner
                )
            )
        }
        out.add(
            KeyPressStep(
                label = "${skill.label} (${skill.formattedKeyBinding()})",
                customKeyCode = skill.keyCode,
                keyLabel = skill.keyLabel,
                holdMs = 30L,
                metaState = skill.metaState,
                targetWebView = skill.owner
            )
        )
        return out
    }
}

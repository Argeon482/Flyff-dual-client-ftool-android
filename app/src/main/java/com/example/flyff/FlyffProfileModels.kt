package com.example.flyff

import android.view.KeyEvent
import com.example.dualweb.DualLayoutMode
import com.example.model.KeyboardKeyCatalog
import com.example.model.WebViewTarget
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class SkillCategory(val displayLabel: String, val shortLabel: String) {
    HEAL("Heal", "Heal"),
    PARTY_BUFF("Party Buff", "Buff"),
    SELF_BUFF("Self Buff", "Self"),
    HP_POT("HP Food / Pot", "HP Pot"),
    MP_POT("MP Pot / Refresher", "MP Pot"),
    FP_POT("FP Vital Drink", "FP Pot"),
    ATTACK_AOE("Attack / AoE", "Attack"),
    COOLDOWN_SKILL("Timed Cooldown Skill", "CD Skill"),
    UTILITY("Utility / Other", "Util")
}

@Serializable
enum class VitalType(val displayLabel: String, val shortLabel: String) {
    HP("HP Bar", "HP"),
    MP("MP Bar", "MP"),
    FP("FP Bar", "FP")
}

@Serializable
enum class TaskbarGroupKey(
    val displayLabel: String,
    val shortLabel: String,
    val metaState: Int
) {
    MAIN_1_0("Main Bar (1–0)", "1–0", 0),
    ALT_1_0("2nd Bar (Alt+1–0)", "Alt+1–0", KeyEvent.META_ALT_ON),
    CTRL_1_0("3rd Bar (Ctrl+1–0)", "Ctrl+1–0", KeyEvent.META_CTRL_ON)
}

@Serializable
data class RectSpec(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val normLeft: Float = 0f,
    val normTop: Float = 0f,
    val normRight: Float = 0f,
    val normBottom: Float = 0f
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isValid: Boolean get() = width > 2 && height > 2

    fun toAndroidRect(screenW: Int, screenH: Int): android.graphics.Rect {
        if (normRight > normLeft && normBottom > normTop && screenW > 0 && screenH > 0) {
            return android.graphics.Rect(
                (normLeft * screenW).toInt().coerceIn(0, screenW - 1),
                (normTop * screenH).toInt().coerceIn(0, screenH - 1),
                (normRight * screenW).toInt().coerceIn(1, screenW),
                (normBottom * screenH).toInt().coerceIn(1, screenH)
            )
        }
        return android.graphics.Rect(
            left.coerceIn(0, (screenW - 1).coerceAtLeast(0)),
            top.coerceIn(0, (screenH - 1).coerceAtLeast(0)),
            right.coerceIn(1, screenW.coerceAtLeast(1)),
            bottom.coerceIn(1, screenH.coerceAtLeast(1))
        )
    }

    companion object {
        fun fromPixels(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            screenW: Int,
            screenH: Int
        ): RectSpec {
            val safeW = screenW.coerceAtLeast(1).toFloat()
            val safeH = screenH.coerceAtLeast(1).toFloat()
            val l = left.coerceIn(0, screenW)
            val t = top.coerceIn(0, screenH)
            val r = right.coerceIn(l, screenW)
            val b = bottom.coerceIn(t, screenH)
            return RectSpec(
                left = l,
                top = t,
                right = r,
                bottom = b,
                normLeft = l / safeW,
                normTop = t / safeH,
                normRight = r / safeW,
                normBottom = b / safeH
            )
        }
    }
}

@Serializable
data class BarCalibrationSpec(
    val vitalType: VitalType,
    val rect: RectSpec,
    val referenceHue: Float = when (vitalType) {
        VitalType.HP -> 4f
        VitalType.MP -> 212f
        VitalType.FP -> 115f
    },
    val minSaturation: Float = 0.28f,
    val minValue: Float = 0.24f
)

@Serializable
data class CharacterHudRegions(
    val owner: WebViewTarget = WebViewTarget.ACCOUNT_1,
    val paneRect: RectSpec = RectSpec(),
    val hpBar: BarCalibrationSpec? = null,
    val mpBar: BarCalibrationSpec? = null,
    val fpBar: BarCalibrationSpec? = null,
    val buffGridRegion: RectSpec = RectSpec(),
    val estimatedTileWidth: Int = 44,
    val estimatedTileHeight: Int = 44
) {
    fun barFor(type: VitalType): BarCalibrationSpec? = when (type) {
        VitalType.HP -> hpBar
        VitalType.MP -> mpBar
        VitalType.FP -> fpBar
    }
}

@Serializable
data class LayoutHudCalibration(
    val layoutMode: DualLayoutMode = DualLayoutMode.SPLIT_50_50,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 1920,
    val charHuds: Map<WebViewTarget, CharacterHudRegions> = emptyMap(),
    val attackToggleRegion: RectSpec? = null,
    val attackToggleTemplateId: String? = null,
    val capturedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ScannedSkillKey(
    val id: String = UUID.randomUUID().toString(),
    val owner: WebViewTarget = WebViewTarget.ACCOUNT_2,
    val label: String = "Skill",
    val category: SkillCategory = SkillCategory.PARTY_BUFF,
    val keyCode: Int = KeyEvent.KEYCODE_1,
    val keyLabel: String = "1",
    val metaState: Int = 0,
    val barPage: Int? = null,
    val groupKey: TaskbarGroupKey = TaskbarGroupKey.MAIN_1_0,
    val slotIndex: Int = 0,
    val iconTemplateId: String? = null
) {
    fun formattedKeyBinding(): String {
        val pagePrefix = barPage?.let { "F$it→" } ?: ""
        val modPrefix = KeyboardKeyCatalog.formatMetaPrefix(metaState)
        val resolvedKey = keyLabel.ifBlank { KeyboardKeyCatalog.labelForKeyCode(keyCode) }
        return "$pagePrefix$modPrefix$resolvedKey"
    }

    fun shortBadge(): String = "${owner.shortLabel}·${formattedKeyBinding()}"

    companion object {
        fun keyCodeForSlotIndex(slotIndex: Int): Pair<Int, String> = when (slotIndex) {
            0 -> KeyEvent.KEYCODE_1 to "1"
            1 -> KeyEvent.KEYCODE_2 to "2"
            2 -> KeyEvent.KEYCODE_3 to "3"
            3 -> KeyEvent.KEYCODE_4 to "4"
            4 -> KeyEvent.KEYCODE_5 to "5"
            5 -> KeyEvent.KEYCODE_6 to "6"
            6 -> KeyEvent.KEYCODE_7 to "7"
            7 -> KeyEvent.KEYCODE_8 to "8"
            8 -> KeyEvent.KEYCODE_9 to "9"
            else -> KeyEvent.KEYCODE_0 to "0"
        }

        fun keyCodeForFPage(page: Int): Int =
            KeyEvent.KEYCODE_F1 + (page.coerceIn(1, 9) - 1)
    }
}

@Serializable
data class ActiveBuffDefinition(
    val id: String = UUID.randomUUID().toString(),
    val targetChar: WebViewTarget = WebViewTarget.ACCOUNT_1,
    val label: String = "Buff",
    val perLayoutTemplateIds: Map<DualLayoutMode, String> = emptyMap(),
    val previewTemplateId: String? = null,
    val lastDetectedSeconds: Int? = null
) {
    fun templateIdForLayout(layout: DualLayoutMode): String? =
        perLayoutTemplateIds[layout] ?: perLayoutTemplateIds.values.firstOrNull() ?: previewTemplateId
}

@Serializable
data class BuffKeyLink(
    val id: String = UUID.randomUUID().toString(),
    val skillKeyId: String,
    val activeBuffId: String,
    val refreshBelowSec: Int = 90,
    val priority: Int = 10,
    val castGuardMs: Long = 950L,
    val requiresSafeHp: Boolean = true,
    val minConfidencePct: Int = 82,
    val enabled: Boolean = true
)

@Serializable
data class HealRuleConfig(
    val enabled: Boolean = true,
    val targetChar: WebViewTarget = WebViewTarget.ACCOUNT_1,
    val healSkillKeyId: String? = null,
    val triggerBelowPct: Int = 85,
    val safeBuffMinHpPct: Int = 92,
    val healCadenceMs: Long = 220L,
    val postHealStabilizeChecks: Int = 2,
    val secondaryEnabled: Boolean = false,
    val secondaryTargetChar: WebViewTarget = WebViewTarget.ACCOUNT_2,
    val secondaryHealSkillKeyId: String? = null,
    val secondaryTriggerBelowPct: Int = 78
)

@Serializable
data class VitalTriggerRule(
    val id: String = UUID.randomUUID().toString(),
    val targetChar: WebViewTarget = WebViewTarget.ACCOUNT_1,
    val vitalType: VitalType = VitalType.MP,
    val triggerBelowPct: Int = 40,
    val skillKeyId: String? = null,
    val cooldownMs: Long = 1500L,
    val enabled: Boolean = true
)

@Serializable
data class CooldownSkillRule(
    val id: String = UUID.randomUUID().toString(),
    val skillKeyId: String,
    val cooldownMs: Long = 8000L,
    val onlyWhenCombatActive: Boolean = true,
    val onlyWhenSafeHp: Boolean = true,
    val enabled: Boolean = true
)

@Serializable
data class CombatRotationConfig(
    val enabled: Boolean = false,
    val useScreenToggleMatch: Boolean = false,
    val manualCombatEnabled: Boolean = false,
    val toggleMinConfidencePct: Int = 85,
    val attackSkillKeyIds: List<String> = emptyList(),
    val attackIntervalMs: Long = 350L,
    val cooldownSkills: List<CooldownSkillRule> = emptyList()
)

@Serializable
data class FlyffProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Flyff Smart Profile",
    val char1Label: String = "Fighter (A1)",
    val char2Label: String = "Buffer / RM (A2)",
    val pollingIntervalMs: Long = 160L,
    val buffScanIntervalMs: Long = 1000L,
    val skillKeys: List<ScannedSkillKey> = emptyList(),
    val activeBuffs: List<ActiveBuffDefinition> = emptyList(),
    val buffLinks: List<BuffKeyLink> = emptyList(),
    val layouts: Map<DualLayoutMode, LayoutHudCalibration> = emptyMap(),
    val healRule: HealRuleConfig = HealRuleConfig(),
    val vitalTriggers: List<VitalTriggerRule> = emptyList(),
    val combatConfig: CombatRotationConfig = CombatRotationConfig(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun skillById(id: String?): ScannedSkillKey? =
        if (id == null) null else skillKeys.firstOrNull { it.id == id }

    fun buffById(id: String?): ActiveBuffDefinition? =
        if (id == null) null else activeBuffs.firstOrNull { it.id == id }

    fun calibratedLayoutsSummary(): String {
        if (layouts.isEmpty()) return "No HUD calibrated"
        return layouts.keys.joinToString(" · ") { it.shortLabel }
    }
}

object FlyffLabelPresets {
    val quickSkillLabels: List<Pair<String, SkillCategory>> = listOf(
        "Heal" to SkillCategory.HEAL,
        "Haste" to SkillCategory.PARTY_BUFF,
        "Patience" to SkillCategory.PARTY_BUFF,
        "Quick Step" to SkillCategory.PARTY_BUFF,
        "Mental Sign" to SkillCategory.PARTY_BUFF,
        "Heap Up" to SkillCategory.PARTY_BUFF,
        "Cat's Reflex" to SkillCategory.PARTY_BUFF,
        "Protect" to SkillCategory.PARTY_BUFF,
        "Cannon Ball" to SkillCategory.PARTY_BUFF,
        "Spirit Fortune" to SkillCategory.PARTY_BUFF,
        "Beef Up" to SkillCategory.PARTY_BUFF,
        "Accuracy" to SkillCategory.PARTY_BUFF,
        "Holycross" to SkillCategory.COOLDOWN_SKILL,
        "Crucio" to SkillCategory.SELF_BUFF,
        "Prevention" to SkillCategory.PARTY_BUFF,
        "HP Food" to SkillCategory.HP_POT,
        "MP Pot" to SkillCategory.MP_POT,
        "FP Pot" to SkillCategory.FP_POT,
        "AoE Attack" to SkillCategory.ATTACK_AOE,
        "Single Attack" to SkillCategory.ATTACK_AOE
    )

    val quickBuffLabels: List<String> = listOf(
        "Haste",
        "Patience",
        "Quick Step",
        "Mental Sign",
        "Heap Up",
        "Cat's Reflex",
        "Protect",
        "Cannon Ball",
        "Spirit Fortune",
        "Beef Up",
        "Accuracy",
        "Crucio",
        "Prevention",
        "Stonehand",
        "GT"
    )
}

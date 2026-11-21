package com.example

import android.view.KeyEvent
import com.example.dualweb.DualLayoutMode
import com.example.flyff.ActiveBuffDefinition
import com.example.flyff.BarCalibrationSpec
import com.example.flyff.BuffKeyLink
import com.example.flyff.BuffTimerOcrReader
import com.example.flyff.CharacterHudRegions
import com.example.flyff.CombatRotationConfig
import com.example.flyff.FlyffMacroCompiler
import com.example.flyff.FlyffProfile
import com.example.flyff.HealRuleConfig
import com.example.flyff.LayoutHudCalibration
import com.example.flyff.RectSpec
import com.example.flyff.ScannedSkillKey
import com.example.flyff.SkillCategory
import com.example.flyff.VitalTriggerRule
import com.example.flyff.VitalType
import com.example.model.IfBarPercentStep
import com.example.model.IfBuffTimerStep
import com.example.model.WebViewTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlyffSmartEngineUnitTest {

    @Test
    fun parseFlyffTimerSeconds_handlesStandardAndNoisyOcr() {
        assertEquals(852, BuffTimerOcrReader.parseFlyffTimerSeconds("14:12"))
        assertEquals(120, BuffTimerOcrReader.parseFlyffTimerSeconds("02:00"))
        assertEquals(15, BuffTimerOcrReader.parseFlyffTimerSeconds("00:15"))
        assertEquals(14400, BuffTimerOcrReader.parseFlyffTimerSeconds("4 Hr"))
        assertEquals(86400, BuffTimerOcrReader.parseFlyffTimerSeconds("1 Day"))
        assertEquals(595, BuffTimerOcrReader.parseFlyffTimerSeconds("O9:55"))
        assertEquals(852, BuffTimerOcrReader.parseFlyffTimerSeconds("14.12"))
        assertEquals(45, BuffTimerOcrReader.parseFlyffTimerSeconds("45s"))
    }

    @Test
    fun compileToMacro_emitsPriorityHealPotsSafeBuffOneAndCombat() {
        val healKey = ScannedSkillKey(
            id = "k_heal",
            owner = WebViewTarget.ACCOUNT_2,
            label = "Heal",
            category = SkillCategory.HEAL,
            keyCode = KeyEvent.KEYCODE_1,
            keyLabel = "1"
        )
        val hasteKey = ScannedSkillKey(
            id = "k_haste",
            owner = WebViewTarget.ACCOUNT_2,
            label = "Haste",
            category = SkillCategory.PARTY_BUFF,
            keyCode = KeyEvent.KEYCODE_2,
            keyLabel = "2"
        )
        val mpPotKey = ScannedSkillKey(
            id = "k_mp",
            owner = WebViewTarget.ACCOUNT_1,
            label = "MP Pot",
            category = SkillCategory.MP_POT,
            keyCode = KeyEvent.KEYCODE_3,
            keyLabel = "3"
        )
        val atkKey = ScannedSkillKey(
            id = "k_atk",
            owner = WebViewTarget.ACCOUNT_1,
            label = "AoE Attack",
            category = SkillCategory.ATTACK_AOE,
            keyCode = KeyEvent.KEYCODE_0,
            keyLabel = "0"
        )
        val hasteBuff = ActiveBuffDefinition(
            id = "b_haste",
            targetChar = WebViewTarget.ACCOUNT_1,
            label = "Haste",
            perLayoutTemplateIds = mapOf(DualLayoutMode.SPLIT_50_50 to "tpl_haste")
        )
        val a1Hud = CharacterHudRegions(
            owner = WebViewTarget.ACCOUNT_1,
            paneRect = RectSpec.fromPixels(0, 960, 1080, 1920, 1080, 1920),
            hpBar = BarCalibrationSpec(VitalType.HP, RectSpec.fromPixels(80, 980, 240, 995, 1080, 1920)),
            mpBar = BarCalibrationSpec(VitalType.MP, RectSpec.fromPixels(80, 1000, 240, 1015, 1080, 1920)),
            fpBar = BarCalibrationSpec(VitalType.FP, RectSpec.fromPixels(80, 1020, 240, 1035, 1080, 1920)),
            buffGridRegion = RectSpec.fromPixels(10, 1060, 320, 1260, 1080, 1920)
        )
        val profile = FlyffProfile(
            name = "Psy + RM",
            skillKeys = listOf(healKey, hasteKey, mpPotKey, atkKey),
            activeBuffs = listOf(hasteBuff),
            buffLinks = listOf(
                BuffKeyLink(
                    skillKeyId = hasteKey.id,
                    activeBuffId = hasteBuff.id,
                    refreshBelowSec = 90
                )
            ),
            layouts = mapOf(
                DualLayoutMode.SPLIT_50_50 to LayoutHudCalibration(
                    layoutMode = DualLayoutMode.SPLIT_50_50,
                    screenWidth = 1080,
                    screenHeight = 1920,
                    charHuds = mapOf(WebViewTarget.ACCOUNT_1 to a1Hud)
                )
            ),
            healRule = HealRuleConfig(
                enabled = true,
                targetChar = WebViewTarget.ACCOUNT_1,
                healSkillKeyId = healKey.id,
                triggerBelowPct = 85,
                safeBuffMinHpPct = 92
            ),
            vitalTriggers = listOf(
                VitalTriggerRule(
                    targetChar = WebViewTarget.ACCOUNT_1,
                    vitalType = VitalType.MP,
                    triggerBelowPct = 40,
                    skillKeyId = mpPotKey.id
                )
            ),
            combatConfig = CombatRotationConfig(
                enabled = true,
                attackSkillKeyIds = listOf(atkKey.id)
            )
        )

        val macro = FlyffMacroCompiler.compileToMacro(profile, DualLayoutMode.SPLIT_50_50)
        val fnNames = macro.functions.map { it.name }.toSet()
        assertTrue(fnNames.contains("priority_heal"))
        assertTrue(fnNames.contains("vital_pots"))
        assertTrue(fnNames.contains("safe_buff_one"))
        assertTrue(fnNames.contains("combat_rotation"))

        val healFn = macro.functions.first { it.name == "priority_heal" }
        assertNotNull(healFn.steps.firstOrNull { it is IfBarPercentStep })

        val buffFn = macro.functions.first { it.name == "safe_buff_one" }
        assertNotNull(buffFn.steps.firstOrNull { it is IfBuffTimerStep })
    }
}

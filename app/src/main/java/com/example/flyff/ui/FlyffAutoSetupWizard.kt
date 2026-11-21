package com.example.flyff.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsKabaddi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.TemplateAssetEntity
import com.example.dualweb.DualLayoutMode
import com.example.flyff.BuffKeyLink
import com.example.flyff.CooldownSkillRule
import com.example.flyff.FlyffProfile
import com.example.flyff.SkillCategory
import com.example.flyff.VitalTriggerRule
import com.example.flyff.VitalType
import com.example.model.MacroDefinition
import com.example.model.WebViewTarget
import com.example.ui.theme.AmberLogic
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldRun
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.RoseAlert
import com.example.ui.theme.SlateElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateWell
import com.example.ui.theme.TextDim
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletVar

/**
 * 5-Step Guided Flyff Auto-Setup Wizard:
 * 1. Profile & Roles
 * 2. Original View Key Auto-Scan (1–0, Alt, Ctrl, F1–F9 + manual A1/A2 keys)
 * 3. Mobile UI HUD & Active Buff Auto-Crop (Unified 50/50 Split + Single A1 + Single A2)
 * 4. Link Buff Keys <-> Active Buff Icons (with timer refresh thresholds & priorities)
 * 5. Priority Heal, HP/MP/FP Pots, Fighter Self-Buffs / Attack Toggle & Cooldown Skills
 */
@Composable
fun FlyffAutoSetupWizard(
    controller: FlyffSetupController,
    templates: List<TemplateAssetEntity>,
    canLiveCapture: Boolean,
    screenCaptureActive: Boolean,
    onRequestScreenCapture: () -> Unit,
    onCaptureOriginalView: (ownerOverride: WebViewTarget?) -> Unit,
    onSelectHudLayoutAndSwitchView: (DualLayoutMode) -> Unit,
    onCaptureMobileHud: (DualLayoutMode) -> Unit,
    onSaveAndRunSmart: (FlyffProfile) -> Unit,
    onExportedToMacro: (MacroDefinition) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by controller.uiState.collectAsState()
    val stepTitles = listOf("1. Profile", "2. Keys", "3. HUD & Buffs", "4. Link Buffs", "5. Rules")

    Surface(
        color = ObsidianBg.copy(alpha = 0.97f),
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("flyff_auto_setup_wizard")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Wizard Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlateSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(22.dp))
                    Column {
                        Text(
                            text = "Flyff Auto-Setup Wizard",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${state.profile.name} · ${state.profile.skillKeys.size} keys · ${state.profile.buffLinks.size} linked buffs",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("wizard_close_btn")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close Auto-Setup Wizard", tint = TextSecondary)
                }
            }

            // Stepper Chips Bar
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlateSurface)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            ) {
                stepTitles.forEachIndexed { idx, title ->
                    val active = state.stepIndex == idx
                    FilterChip(
                        selected = active,
                        onClick = { controller.setStep(idx) },
                        label = { Text(title, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                            selectedLabelColor = ElectricCyan
                        ),
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("wizard_step_tab_$idx")
                    )
                }
            }

            if (state.isBusy) {
                LinearProgressIndicator(
                    color = ElectricCyan,
                    trackColor = SlateWell,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Status / Feedback Banner
            if (!state.statusMessage.isNullOrBlank()) {
                val accent = if (state.statusIsError) RoseAlert else EmeraldRun
                Surface(
                    color = accent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = state.statusMessage.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Step Body
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                when (state.stepIndex) {
                    0 -> FlyffProfileIntroStep(
                        state = state,
                        screenCaptureActive = screenCaptureActive,
                        onRequestScreenCapture = onRequestScreenCapture,
                        onUpdateProfile = { controller.updateProfile(it) },
                        onNextStep = { controller.setStep(1) }
                    )
                    1 -> FlyffKeyScannerStep(
                        state = state,
                        templates = templates,
                        canLiveCapture = canLiveCapture,
                        onCaptureOriginalView = onCaptureOriginalView,
                        onSetScanPage = { controller.setActiveScanPage(it) },
                        onSetSectionGroup = { sec, grp -> controller.setSectionGroup(sec, grp) },
                        onUpdatePendingSlot = { id, fn -> controller.updatePendingSlot(id, fn) },
                        onCommitPendingSlots = { controller.commitPendingSlots() },
                        onSaveManualKey = { controller.addOrUpdateManualSkillKey(it) },
                        onDeleteKey = { controller.removeSkillKey(it) }
                    )
                    2 -> FlyffHudCalibrationStep(
                        state = state,
                        templates = templates,
                        canLiveCapture = canLiveCapture,
                        onSelectLayoutMode = { mode ->
                            controller.setSelectedHudLayout(mode)
                            onSelectHudLayoutAndSwitchView(mode)
                        },
                        onCaptureMobileHud = onCaptureMobileHud,
                        onNudgeHud = { mode, owner, tgt, dx, dy, dw, dh ->
                            controller.nudgeHudRegion(mode, owner, tgt, dx, dy, dw, dh)
                        },
                        onReScanBuffGrid = { mode -> controller.reScanBuffTilesFromPreview(mode) },
                        onUpdatePendingBuff = { id, fn -> controller.updatePendingBuff(id, fn) },
                        onCommitPendingBuffs = { mode -> controller.commitPendingBuffs(mode) },
                        onDeleteActiveBuff = { controller.removeActiveBuff(it) }
                    )
                    3 -> FlyffBuffLinkerStep(
                        state = state,
                        onAutoLink = { controller.autoLinkBuffsByName() },
                        onAddLink = { skillId, buffId, sec -> controller.addBuffLink(skillId, buffId, sec) },
                        onUpdateLink = { controller.updateBuffLink(it) },
                        onRemoveLink = { controller.removeBuffLink(it) }
                    )
                    4 -> FlyffRulesAndCombatStep(
                        state = state,
                        onUpdateHeal = { controller.updateHealRule(it) },
                        onAddVitalRule = { controller.addVitalTrigger(it) },
                        onUpdateVitalRule = { controller.updateVitalTrigger(it) },
                        onRemoveVitalRule = { controller.removeVitalTrigger(it) },
                        onUpdateCombat = { controller.updateCombatConfig(it) },
                        onAddCooldownSkill = { controller.addCooldownSkill(it) },
                        onRemoveCooldownSkill = { controller.removeCooldownSkill(it) }
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // Sticky Bottom Action Bar
            HorizontalDivider(color = HairlineBorder)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlateSurface)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                OutlinedButton(
                    onClick = { controller.saveProfile() },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("wizard_save_profile_btn")
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save", color = TextPrimary, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        controller.exportToMacro(
                            layoutMode = state.selectedHudLayout,
                            onExported = onExportedToMacro
                        )
                    },
                    border = BorderStroke(1.dp, AmberLogic.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("wizard_export_macro_btn")
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = AmberLogic, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Export Macro", color = AmberLogic, maxLines = 1)
                }

                Button(
                    onClick = {
                        controller.saveProfile { saved ->
                            onSaveAndRunSmart(saved)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = Color(0xFF031911)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("wizard_save_and_run_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save & Run", fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun FlyffProfileIntroStep(
    state: FlyffSetupUiState,
    screenCaptureActive: Boolean,
    onRequestScreenCapture: () -> Unit,
    onUpdateProfile: ((FlyffProfile) -> FlyffProfile) -> Unit,
    onNextStep: () -> Unit
) {
    val profile = state.profile
    Surface(
        color = SlateSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = "STEP 1 · PROFILE & CHARACTER ROLES",
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "One unified Smart Profile stores both characters' skill keys, Single & Split Mobile UI HUD calibrations, active buff timers, priority healing, pots, and combat triggers.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Surface(
                color = if (screenCaptureActive) EmeraldRun.copy(alpha = 0.14f) else AmberLogic.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (screenCaptureActive) EmeraldRun else AmberLogic),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (screenCaptureActive) "Screen Capture Active" else "Screen Capture Required",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (screenCaptureActive) EmeraldRun else AmberLogic,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (screenCaptureActive) {
                                "Ready to auto-crop taskbar keys, HP/MP/FP bars, and OCR buff timers."
                            } else {
                                "Grant Screen Capture permission before capturing keys or HUD."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    if (!screenCaptureActive) {
                        Button(
                            onClick = onRequestScreenCapture,
                            colors = ButtonDefaults.buttonColors(containerColor = AmberLogic, contentColor = ObsidianBg),
                            modifier = Modifier
                                .height(44.dp)
                                .testTag("wizard_grant_capture_btn")
                        ) {
                            Text("Enable", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = profile.name,
                onValueChange = { v -> onUpdateProfile { it.copy(name = v) } },
                label = { Text("Profile Name") },
                placeholder = { Text("e.g. Psy + RM Azria") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wizard_profile_name_input")
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = profile.char1Label,
                    onValueChange = { v -> onUpdateProfile { it.copy(char1Label = v) } },
                    label = { Text("Char 1 (A1) Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = profile.char2Label,
                    onValueChange = { v -> onUpdateProfile { it.copy(char2Label = v) } },
                    label = { Text("Char 2 (A2) Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = profile.pollingIntervalMs.toString(),
                    onValueChange = { v ->
                        val ms = v.filter(Char::isDigit).toLongOrNull() ?: return@OutlinedTextField
                        onUpdateProfile { it.copy(pollingIntervalMs = ms.coerceIn(80L, 1000L)) }
                    },
                    label = { Text("HP/Pot Poll (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = profile.buffScanIntervalMs.toString(),
                    onValueChange = { v ->
                        val ms = v.filter(Char::isDigit).toLongOrNull() ?: return@OutlinedTextField
                        onUpdateProfile { it.copy(buffScanIntervalMs = ms.coerceIn(500L, 5000L)) }
                    },
                    label = { Text("Buff OCR Poll (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = onNextStep,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = ObsidianBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("wizard_next_to_keys_btn")
            ) {
                Text("Next: Scan Original View Keys →", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlyffBuffLinkerStep(
    state: FlyffSetupUiState,
    onAutoLink: () -> Unit,
    onAddLink: (skillKeyId: String, activeBuffId: String, refreshBelowSec: Int) -> Unit,
    onUpdateLink: (BuffKeyLink) -> Unit,
    onRemoveLink: (String) -> Unit
) {
    val profile = state.profile
    val buffKeys = profile.skillKeys.filter {
        it.category == SkillCategory.PARTY_BUFF || it.category == SkillCategory.SELF_BUFF
    }
    var selectedSkillId by remember(buffKeys) { mutableStateOf(buffKeys.firstOrNull()?.id) }
    var selectedBuffId by remember(profile.activeBuffs) { mutableStateOf(profile.activeBuffs.firstOrNull()?.id) }
    var refreshSecInput by remember { mutableStateOf("90") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = SlateWell,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, VioletVar.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STEP 4 · LINK BUFF KEYS ↔ ACTIVE BUFFS",
                        style = MaterialTheme.typography.labelMedium,
                        color = VioletVar,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = onAutoLink,
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("auto_link_buffs_btn")
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Auto-Link", color = ElectricCyan)
                    }
                }

                Text(
                    text = "Pair each buff skill key (A1 self-buff or A2 RM buff) with its active buff icon. The engine reads each icon's live MM:SS timer and casts strictly ONE buff at a time when HP is safe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                if (buffKeys.isEmpty() || profile.activeBuffs.isEmpty()) {
                    Text(
                        text = "Add at least one Buff key in Step 2 and one Active Buff icon in Step 3 to create buff links.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberLogic
                    )
                } else {
                    Text("SELECT BUFF KEY", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        buffKeys.forEach { k ->
                            FilterChip(
                                selected = selectedSkillId == k.id,
                                onClick = { selectedSkillId = k.id },
                                label = { Text("${k.label} (${k.shortBadge()})") }
                            )
                        }
                    }

                    Text("SELECT ACTIVE BUFF ICON TO MONITOR", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        profile.activeBuffs.forEach { b ->
                            FilterChip(
                                selected = selectedBuffId == b.id,
                                onClick = { selectedBuffId = b.id },
                                label = { Text("${b.targetChar.shortLabel}: ${b.label}") }
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = refreshSecInput,
                            onValueChange = { refreshSecInput = it.filter(Char::isDigit) },
                            label = { Text("Refresh <= (sec)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val sId = selectedSkillId
                                val bId = selectedBuffId
                                if (sId != null && bId != null) {
                                    onAddLink(sId, bId, (refreshSecInput.toIntOrNull() ?: 90).coerceIn(5, 3600))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = VioletVar, contentColor = Color.White),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("add_buff_link_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Link Pair", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Linked Buffs List
        Surface(
            color = SlateSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "ACTIVE BUFF LINKS (${profile.buffLinks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                if (profile.buffLinks.isEmpty()) {
                    Text("No buff pairs linked yet.", style = MaterialTheme.typography.bodySmall, color = TextDim)
                } else {
                    profile.buffLinks.sortedBy { it.priority }.forEach { link ->
                        val skill = profile.skillById(link.skillKeyId)
                        val buff = profile.buffById(link.activeBuffId)
                        Surface(
                            color = SlateWell,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (link.enabled) VioletVar.copy(alpha = 0.45f) else HairlineBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${skill?.label ?: "?"} (${skill?.shortBadge() ?: ""}) → ${buff?.targetChar?.shortLabel ?: ""}: ${buff?.label ?: "?"}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Refresh missing or ≤ ${link.refreshBelowSec}s · Guard ${link.castGuardMs}ms · Prio ${link.priority}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = VioletVar,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRemoveLink(link.id) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Remove link", tint = RoseAlert)
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = { onUpdateLink(link.copy(refreshBelowSec = (link.refreshBelowSec - 15).coerceAtLeast(10))) },
                                        modifier = Modifier.height(40.dp)
                                    ) { Text("-15s", color = TextPrimary) }
                                    OutlinedButton(
                                        onClick = { onUpdateLink(link.copy(refreshBelowSec = (link.refreshBelowSec + 15).coerceAtMost(1800))) },
                                        modifier = Modifier.height(40.dp)
                                    ) { Text("+15s", color = TextPrimary) }
                                    Spacer(Modifier.weight(1f))
                                    Text("Safe HP", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    Switch(
                                        checked = link.requiresSafeHp,
                                        onCheckedChange = { onUpdateLink(link.copy(requiresSafeHp = it)) },
                                        colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = EmeraldRun)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlyffRulesAndCombatStep(
    state: FlyffSetupUiState,
    onUpdateHeal: ((com.example.flyff.HealRuleConfig) -> com.example.flyff.HealRuleConfig) -> Unit,
    onAddVitalRule: (VitalTriggerRule) -> Unit,
    onUpdateVitalRule: (VitalTriggerRule) -> Unit,
    onRemoveVitalRule: (String) -> Unit,
    onUpdateCombat: ((com.example.flyff.CombatRotationConfig) -> com.example.flyff.CombatRotationConfig) -> Unit,
    onAddCooldownSkill: (CooldownSkillRule) -> Unit,
    onRemoveCooldownSkill: (String) -> Unit
) {
    val profile = state.profile
    val heal = profile.healRule
    val combat = profile.combatConfig

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        // 1. PRIORITY HEAL RULE
        Surface(
            color = SlateSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, RoseAlert.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = RoseAlert, modifier = Modifier.size(18.dp))
                        Text("PRIORITY 1 · HEALING (ALWAYS FIRST)", style = MaterialTheme.typography.labelMedium, color = RoseAlert, fontWeight = FontWeight.Bold)
                    }
                    Switch(
                        checked = heal.enabled,
                        onCheckedChange = { onUpdateHeal { r -> r.copy(enabled = it) } },
                        colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = RoseAlert),
                        modifier = Modifier.testTag("heal_rule_enable_switch")
                    )
                }

                if (heal.enabled) {
                    Text("MONITOR HP BAR OF", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(WebViewTarget.ACCOUNT_1, WebViewTarget.ACCOUNT_2).forEach { target ->
                            FilterChip(
                                selected = heal.targetChar == target,
                                onClick = { onUpdateHeal { it.copy(targetChar = target) } },
                                label = { Text(target.displayLabel) },
                                modifier = Modifier.weight(1f).height(44.dp)
                            )
                        }
                    }

                    Text("CAST HEAL SKILL KEY", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        profile.skillKeys.forEach { k ->
                            FilterChip(
                                selected = heal.healSkillKeyId == k.id,
                                onClick = { onUpdateHeal { it.copy(healSkillKeyId = k.id) } },
                                label = { Text("${k.label} (${k.shortBadge()})") }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = heal.triggerBelowPct.toString(),
                            onValueChange = { v ->
                                val pct = v.filter(Char::isDigit).toIntOrNull() ?: return@OutlinedTextField
                                onUpdateHeal { it.copy(triggerBelowPct = pct.coerceIn(5, 99)) }
                            },
                            label = { Text("Heal Below HP %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = heal.safeBuffMinHpPct.toString(),
                            onValueChange = { v ->
                                val pct = v.filter(Char::isDigit).toIntOrNull() ?: return@OutlinedTextField
                                onUpdateHeal { it.copy(safeBuffMinHpPct = pct.coerceIn(10, 100)) }
                            },
                            label = { Text("Safe Buff HP ≥ %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Secondary Heal (e.g. RM A2 Self-Heal)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Switch(
                            checked = heal.secondaryEnabled,
                            onCheckedChange = { onUpdateHeal { r -> r.copy(secondaryEnabled = it) } }
                        )
                    }
                    if (heal.secondaryEnabled) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            profile.skillKeys.forEach { k ->
                                FilterChip(
                                    selected = heal.secondaryHealSkillKeyId == k.id,
                                    onClick = { onUpdateHeal { it.copy(secondaryHealSkillKeyId = k.id) } },
                                    label = { Text("${k.label} (${k.shortBadge()})") }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. HP / MP / FP POT & FOOD TRIGGERS
        Surface(
            color = SlateSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            var newTarget by remember { mutableStateOf(WebViewTarget.ACCOUNT_1) }
            var newVital by remember { mutableStateOf(VitalType.MP) }
            var newPct by remember { mutableStateOf("40") }
            var newSkillId by remember(profile.skillKeys) { mutableStateOf(profile.skillKeys.firstOrNull()?.id) }

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                    Text("PRIORITY 2 · HP / MP / FP POT & FOOD TRIGGERS", style = MaterialTheme.typography.labelMedium, color = ElectricCyan, fontWeight = FontWeight.Bold)
                }

                profile.vitalTriggers.forEach { rule ->
                    val skill = profile.skillById(rule.skillKeyId)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SlateWell)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${rule.targetChar.shortLabel} ${rule.vitalType.shortLabel} < ${rule.triggerBelowPct}% → ${skill?.label ?: "Unassigned"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Cooldown ${rule.cooldownMs}ms · ${skill?.shortBadge() ?: ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { onUpdateVitalRule(rule.copy(enabled = it)) }
                        )
                        IconButton(onClick = { onRemoveVitalRule(rule.id) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete trigger", tint = RoseAlert)
                        }
                    }
                }

                if (profile.skillKeys.isNotEmpty()) {
                    HorizontalDivider(color = HairlineBorder)
                    Text("ADD NEW VITAL TRIGGER (A1 OR A2)", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(WebViewTarget.ACCOUNT_1, WebViewTarget.ACCOUNT_2).forEach { t ->
                            FilterChip(selected = newTarget == t, onClick = { newTarget = t }, label = { Text(t.shortLabel) }, modifier = Modifier.weight(1f))
                        }
                        VitalType.entries.forEach { v ->
                            FilterChip(selected = newVital == v, onClick = { newVital = v }, label = { Text(v.shortLabel) }, modifier = Modifier.weight(1f))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        profile.skillKeys.forEach { k ->
                            FilterChip(
                                selected = newSkillId == k.id,
                                onClick = { newSkillId = k.id },
                                label = { Text("${k.label} (${k.shortBadge()})") }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newPct,
                            onValueChange = { newPct = it.filter(Char::isDigit) },
                            label = { Text("Trigger < %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val sId = newSkillId ?: return@Button
                                onAddVitalRule(
                                    VitalTriggerRule(
                                        targetChar = newTarget,
                                        vitalType = newVital,
                                        triggerBelowPct = (newPct.toIntOrNull() ?: 40).coerceIn(5, 99),
                                        skillKeyId = sId
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = ObsidianBg),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("add_vital_trigger_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Pot", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 3. COMBAT, ATTACK TOGGLE & COOLDOWN SKILLS
        Surface(
            color = SlateSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, AmberLogic.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            var cdSkillId by remember(profile.skillKeys) { mutableStateOf(profile.skillKeys.firstOrNull()?.id) }
            var cdMsInput by remember { mutableStateOf("8000") }

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.SportsKabaddi, contentDescription = null, tint = AmberLogic, modifier = Modifier.size(18.dp))
                        Text("PRIORITY 4 · COMBAT & COOLDOWN SKILLS", style = MaterialTheme.typography.labelMedium, color = AmberLogic, fontWeight = FontWeight.Bold)
                    }
                    Switch(
                        checked = combat.enabled,
                        onCheckedChange = { onUpdateCombat { c -> c.copy(enabled = it) } },
                        colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = AmberLogic),
                        modifier = Modifier.testTag("combat_rule_enable_switch")
                    )
                }

                if (combat.enabled) {
                    Text(
                        text = "Use the live ATK pill on the floating bar to toggle attacking on/off while mobbing, or enable Attack Toggle icon matching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Text("ATTACK / AOE SKILL KEYS (TAP TO TOGGLE)", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        profile.skillKeys.forEach { k ->
                            val selected = k.id in combat.attackSkillKeyIds
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    onUpdateCombat { c ->
                                        val next = if (selected) c.attackSkillKeyIds - k.id else c.attackSkillKeyIds + k.id
                                        c.copy(attackSkillKeyIds = next)
                                    }
                                },
                                label = { Text("${k.label} (${k.shortBadge()})") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AmberLogic.copy(alpha = 0.22f),
                                    selectedLabelColor = AmberLogic
                                )
                            )
                        }
                    }

                    OutlinedTextField(
                        value = combat.attackIntervalMs.toString(),
                        onValueChange = { v ->
                            val ms = v.filter(Char::isDigit).toLongOrNull() ?: return@OutlinedTextField
                            onUpdateCombat { it.copy(attackIntervalMs = ms.coerceIn(120L, 5000L)) }
                        },
                        label = { Text("Attack Cadence (ms)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = HairlineBorder)
                    Text("TIMED COOLDOWN SKILLS", style = MaterialTheme.typography.labelSmall, color = TextDim)
                    combat.cooldownSkills.forEach { cd ->
                        val skill = profile.skillById(cd.skillKeyId)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SlateWell)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${skill?.label ?: "?"} (${skill?.shortBadge() ?: ""}) · every ${cd.cooldownMs / 1000}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                            IconButton(onClick = { onRemoveCooldownSkill(cd.id) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete cooldown skill", tint = RoseAlert)
                            }
                        }
                    }

                    if (profile.skillKeys.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            profile.skillKeys.forEach { k ->
                                FilterChip(
                                    selected = cdSkillId == k.id,
                                    onClick = { cdSkillId = k.id },
                                    label = { Text("${k.label} (${k.shortBadge()})") }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = cdMsInput,
                                onValueChange = { cdMsInput = it.filter(Char::isDigit) },
                                label = { Text("Cooldown (ms)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val sId = cdSkillId ?: return@Button
                                    onAddCooldownSkill(
                                        CooldownSkillRule(
                                            skillKeyId = sId,
                                            cooldownMs = (cdMsInput.toLongOrNull() ?: 8000L).coerceIn(500L, 600_000L)
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AmberLogic, contentColor = ObsidianBg),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("add_cooldown_skill_btn")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add CD", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

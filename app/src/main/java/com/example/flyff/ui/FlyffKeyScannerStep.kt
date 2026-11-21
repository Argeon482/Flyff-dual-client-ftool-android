package com.example.flyff.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.TemplateAssetEntity
import com.example.flyff.FlyffLabelPresets
import com.example.flyff.ScannedSkillKey
import com.example.flyff.SkillCategory
import com.example.flyff.TaskbarGroupKey
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlyffKeyScannerStep(
    state: FlyffSetupUiState,
    templates: List<TemplateAssetEntity>,
    canLiveCapture: Boolean,
    onCaptureOriginalView: (ownerOverride: WebViewTarget?) -> Unit,
    onSetScanPage: (Int?) -> Unit,
    onSetKeyBarGroup: (group: TaskbarGroupKey) -> Unit,
    onUpdatePendingSlot: (String, (PendingSlotDraft) -> PendingSlotDraft) -> Unit,
    onCommitPendingSlots: () -> Unit,
    onSaveManualKey: (ScannedSkillKey) -> Unit,
    onDeleteKey: (String) -> Unit
) {
    var captureOwnerOverride by remember { mutableStateOf<WebViewTarget?>(null) }
    var editingManualKey by remember { mutableStateOf<ScannedSkillKey?>(null) }
    var showAddManualDialog by remember { mutableStateOf(false) }

    val templateById = remember(templates) { templates.associateBy { it.id } }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Guided Original View Scan Card
        Surface(
            color = SlateWell,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Text(
                    text = "STEP 2 · ORIGINAL VIEW KEY AUTO-SCAN",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Toggle Flyff Original View so your 10-slot 1–0 keybar (right side of the bottom bar) is visible. Clickable shortcut slots to the left are skipped automatically; switch between 1–0, Alt+1–0, and Ctrl+1–0 in-game and match the modifier below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                // Target Char Auto-Sort selector
                Text("TARGET CHARACTER", style = MaterialTheme.typography.labelSmall, color = TextDim)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val targets = listOf(
                        null to "Auto (View)",
                        WebViewTarget.ACCOUNT_1 to "A1 Only",
                        WebViewTarget.ACCOUNT_2 to "A2 Only"
                    )
                    targets.forEach { (target, label) ->
                        FilterChip(
                            selected = captureOwnerOverride == target,
                            onClick = { captureOwnerOverride = target },
                            label = { Text(label, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan.copy(alpha = 0.18f),
                                selectedLabelColor = ElectricCyan
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("scan_owner_${target?.name?.lowercase() ?: "auto"}")
                        )
                    }
                }

                // F1-F9 Bar Page Confirmation
                Text("TASKBAR PAGE (OPTIONAL F1–F9 AUTO-SWITCH)", style = MaterialTheme.typography.labelSmall, color = TextDim)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = state.activeScanPage == null,
                        onClick = { onSetScanPage(null) },
                        label = { Text("Current") },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("scan_fpage_current")
                    )
                    (1..9).forEach { page ->
                        FilterChip(
                            selected = state.activeScanPage == page,
                            onClick = { onSetScanPage(page) },
                            label = { Text("F$page") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberLogic.copy(alpha = 0.2f),
                                selectedLabelColor = AmberLogic
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("scan_fpage_$page")
                        )
                    }
                }

                // Single 10-slot Key Bar Modifier (Main 1-0 / Alt+1-0 / Ctrl+1-0 share the same 10-slot location)
                Text("ACTIVE KEY BAR (MATCHES SMALL BAR NUMBER)", style = MaterialTheme.typography.labelSmall, color = TextDim)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TaskbarGroupKey.entries.forEach { group ->
                        FilterChip(
                            selected = state.activeKeyBarGroup == group,
                            onClick = { onSetKeyBarGroup(group) },
                            label = { Text(group.shortLabel, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan.copy(alpha = 0.20f),
                                selectedLabelColor = ElectricCyan
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("keybar_group_${group.name.lowercase()}")
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { onCaptureOriginalView(captureOwnerOverride) },
                        enabled = !state.isBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = ObsidianBg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                            .testTag("capture_original_view_keys_btn")
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (canLiveCapture) "Capture & Crop Keys" else "Capture (Open Dual Flyff)",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = { showAddManualDialog = true },
                        border = BorderStroke(1.dp, EmeraldRun.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(0.85f)
                            .height(48.dp)
                            .testTag("add_manual_skill_key_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = EmeraldRun, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Manual Key", color = TextPrimary, maxLines = 1)
                    }
                }
            }
        }

        // Zoomed 10-Slot 1-0 Keybar Crop Verification Preview
        state.taskbarPreviews.forEach { preview ->
            if (!preview.stripBitmap.isRecycled) {
                TaskbarStripPreviewCard(
                    preview = preview,
                    groupKey = state.activeKeyBarGroup
                )
            }
        }

        // Auto-Cropped Pending Slot Labeling Deck
        if (state.pendingSlots.isNotEmpty()) {
            Surface(
                color = SlateSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AmberLogic.copy(alpha = 0.55f)),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AUTO-CROPPED SLOTS (${state.pendingSlots.count { it.include }}/${state.pendingSlots.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = AmberLogic,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tap a preset or type a name & category for each slot, then Save.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Button(
                            onClick = onCommitPendingSlots,
                            enabled = !state.isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldRun, contentColor = Color(0xFF031911)),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("commit_cropped_slots_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save Slots", fontWeight = FontWeight.Bold)
                        }
                    }

                    state.pendingSlots.forEach { slot ->
                        PendingSlotCard(
                            slot = slot,
                            onUpdate = { transform -> onUpdatePendingSlot(slot.tempId, transform) }
                        )
                    }
                }
            }
        }

        // Saved Roster Grouped by A1 & A2
        val a1Keys = state.profile.skillKeys.filter { it.owner == WebViewTarget.ACCOUNT_1 }
        val a2Keys = state.profile.skillKeys.filter { it.owner == WebViewTarget.ACCOUNT_2 }

        SkillRosterSection(
            title = "${state.profile.char1Label} Keys (${a1Keys.size})",
            accent = ElectricCyan,
            keys = a1Keys,
            templateById = templateById,
            onEdit = { editingManualKey = it },
            onDelete = onDeleteKey
        )

        SkillRosterSection(
            title = "${state.profile.char2Label} Keys (${a2Keys.size})",
            accent = EmeraldRun,
            keys = a2Keys,
            templateById = templateById,
            onEdit = { editingManualKey = it },
            onDelete = onDeleteKey
        )
    }

    if (showAddManualDialog || editingManualKey != null) {
        ManualSkillKeyDialog(
            existing = editingManualKey,
            onDismiss = {
                showAddManualDialog = false
                editingManualKey = null
            },
            onSave = { key ->
                onSaveManualKey(key)
                showAddManualDialog = false
                editingManualKey = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingSlotCard(
    slot: PendingSlotDraft,
    onUpdate: ((PendingSlotDraft) -> PendingSlotDraft) -> Unit
) {
    val ownerAccent = if (slot.owner == WebViewTarget.ACCOUNT_1) ElectricCyan else EmeraldRun
    val imgBitmap = remember(slot.iconBitmap) { slot.iconBitmap.asImageBitmap() }

    Surface(
        color = SlateWell,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (slot.include) ownerAccent.copy(alpha = 0.45f) else HairlineBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = slot.include,
                    onCheckedChange = { checked -> onUpdate { it.copy(include = checked) } },
                    colors = CheckboxDefaults.colors(checkedColor = EmeraldRun, checkmarkColor = ObsidianBg),
                    modifier = Modifier.testTag("pending_slot_check_${slot.tempId}")
                )
                Image(
                    bitmap = imgBitmap,
                    contentDescription = "Cropped skill icon for ${slot.keyBindingPreview()}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ObsidianBg)
                        .border(1.dp, ownerAccent, RoundedCornerShape(8.dp))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${slot.owner.shortLabel} · Key ${slot.keyBindingPreview()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ownerAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${slot.iconBitmap.width}×${slot.iconBitmap.height}px",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextDim,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    OutlinedTextField(
                        value = slot.label,
                        onValueChange = { v -> onUpdate { it.copy(label = v) } },
                        singleLine = true,
                        label = { Text("Skill / Item Label") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pending_slot_label_${slot.tempId}")
                    )
                }
            }

            if (slot.include) {
                // Quick Category chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    SkillCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = slot.category == cat,
                            onClick = { onUpdate { it.copy(category = cat) } },
                            label = { Text(cat.shortLabel, maxLines = 1) },
                            modifier = Modifier.height(40.dp)
                        )
                    }
                }

                // 1-Tap Flyff Label Presets
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    FlyffLabelPresets.quickSkillLabels.forEach { (presetName, presetCat) ->
                        Surface(
                            onClick = { onUpdate { it.copy(label = presetName, category = presetCat) } },
                            color = SlateElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (slot.label.equals(presetName, true)) ElectricCyan else HairlineBorder
                            )
                        ) {
                            Text(
                                text = presetName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (slot.label.equals(presetName, true)) ElectricCyan else TextSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillRosterSection(
    title: String,
    accent: Color,
    keys: List<ScannedSkillKey>,
    templateById: Map<String, TemplateAssetEntity>,
    onEdit: (ScannedSkillKey) -> Unit,
    onDelete: (String) -> Unit
) {
    Surface(
        color = SlateSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                fontWeight = FontWeight.Bold
            )
            if (keys.isEmpty()) {
                Text(
                    text = "No keys saved for this character yet. Capture Original View or tap Manual Key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim
                )
            } else {
                keys.forEach { key ->
                    val tpl = key.iconTemplateId?.let { templateById[it] }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SlateWell)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        if (tpl != null) {
                            CompactTemplateIcon(
                                imagePath = tpl.filePath,
                                contentDescription = key.label,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(accent.copy(alpha = 0.16f))
                            ) {
                                Icon(Icons.Default.Keyboard, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = key.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${key.formattedKeyBinding()} · ${key.category.displayLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = categoryAccent(key.category),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        IconButton(
                            onClick = { onEdit(key) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("edit_skill_key_${key.id}")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit ${key.label}", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { onDelete(key.id) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("delete_skill_key_${key.id}")
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${key.label}", tint = RoseAlert, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualSkillKeyDialog(
    existing: ScannedSkillKey?,
    onDismiss: () -> Unit,
    onSave: (ScannedSkillKey) -> Unit
) {
    var owner by remember(existing) { mutableStateOf(existing?.owner ?: WebViewTarget.ACCOUNT_2) }
    var label by remember(existing) { mutableStateOf(existing?.label ?: "") }
    var category by remember(existing) { mutableStateOf(existing?.category ?: SkillCategory.PARTY_BUFF) }
    var slotIndex by remember(existing) { mutableIntStateOf(existing?.slotIndex ?: 0) }
    var groupKey by remember(existing) { mutableStateOf(existing?.groupKey ?: TaskbarGroupKey.MAIN_1_0) }
    var barPage by remember(existing) { mutableStateOf(existing?.barPage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateSurface,
        title = {
            Text(
                text = if (existing == null) "Add Key Binding" else "Edit Key Binding",
                color = TextPrimary
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(WebViewTarget.ACCOUNT_1 to "Char 1 (A1)", WebViewTarget.ACCOUNT_2 to "Char 2 (A2)").forEach { (t, lbl) ->
                        FilterChip(
                            selected = owner == t,
                            onClick = { owner = t },
                            label = { Text(lbl) },
                            modifier = Modifier.weight(1f).height(44.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Skill / Item Name") },
                    placeholder = { Text("e.g. Heal, Haste, MP Pot, AoE") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("manual_key_label_input")
                )

                Text("QUICK PRESETS", style = MaterialTheme.typography.labelSmall, color = TextDim)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FlyffLabelPresets.quickSkillLabels.take(12).forEach { (presetName, presetCat) ->
                        FilterChip(
                            selected = label.equals(presetName, true),
                            onClick = { label = presetName; category = presetCat },
                            label = { Text(presetName) }
                        )
                    }
                }

                Text("CATEGORY", style = MaterialTheme.typography.labelSmall, color = TextDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkillCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.shortLabel) }
                        )
                    }
                }

                HorizontalDivider(color = HairlineBorder)
                Text("BAR MODIFIER", style = MaterialTheme.typography.labelSmall, color = TextDim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    TaskbarGroupKey.entries.forEach { grp ->
                        FilterChip(
                            selected = groupKey == grp,
                            onClick = { groupKey = grp },
                            label = { Text(grp.shortLabel, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text("SLOT KEY (1–0)", style = MaterialTheme.typography.labelSmall, color = TextDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..9).forEach { idx ->
                        val (_, kLbl) = ScannedSkillKey.keyCodeForSlotIndex(idx)
                        FilterChip(
                            selected = slotIndex == idx,
                            onClick = { slotIndex = idx },
                            label = { Text(kLbl) }
                        )
                    }
                }

                Text("F-BAR PAGE (OPTIONAL)", style = MaterialTheme.typography.labelSmall, color = TextDim)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(selected = barPage == null, onClick = { barPage = null }, label = { Text("None") })
                    (1..9).forEach { p ->
                        FilterChip(selected = barPage == p, onClick = { barPage = p }, label = { Text("F$p") })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val (code, kLbl) = ScannedSkillKey.keyCodeForSlotIndex(slotIndex)
                    onSave(
                        (existing ?: ScannedSkillKey()).copy(
                            owner = owner,
                            label = label.trim().ifBlank { "Key $kLbl" },
                            category = category,
                            keyCode = code,
                            keyLabel = kLbl,
                            metaState = groupKey.metaState,
                            barPage = barPage,
                            groupKey = groupKey,
                            slotIndex = slotIndex
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = ObsidianBg),
                modifier = Modifier.testTag("manual_key_save_btn")
            ) {
                Text("Save Key", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun TaskbarStripPreviewCard(
    preview: TaskbarPreviewCapture,
    groupKey: TaskbarGroupKey
) {
    val imgBitmap = remember(preview.stripBitmap) { preview.stripBitmap.asImageBitmap() }
    val stripW = preview.stripRect.width().coerceAtLeast(1).toFloat()
    val stripH = preview.stripRect.height().coerceAtLeast(1).toFloat()
    val aspect = (stripW / stripH).coerceIn(2.5f, 12f)
    val accent = if (preview.owner == WebViewTarget.ACCOUNT_1) ElectricCyan else EmeraldRun

    Surface(
        color = SlateWell,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${preview.owner.shortLabel} · DETECTED ${groupKey.shortLabel} KEYBAR (${preview.slotRects.size}/10)",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Green = Exact Crop",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianBg)
            ) {
                Image(
                    bitmap = imgBitmap,
                    contentDescription = "Captured 1-0 keybar strip for ${preview.owner.shortLabel}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    preview.slotRects.forEach { (_, slotRect) ->
                        val l = ((slotRect.left - preview.stripRect.left) / stripW) * size.width
                        val t = ((slotRect.top - preview.stripRect.top) / stripH) * size.height
                        val w = (slotRect.width() / stripW) * size.width
                        val h = (slotRect.height() / stripH) * size.height
                        if (w > 1f && h > 1f) {
                            drawRect(
                                color = EmeraldRun,
                                topLeft = Offset(l, t),
                                size = Size(w, h),
                                style = Stroke(width = 3f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun categoryAccent(cat: SkillCategory): Color = when (cat) {
    SkillCategory.HEAL -> RoseAlert
    SkillCategory.PARTY_BUFF -> EmeraldRun
    SkillCategory.SELF_BUFF -> VioletVar
    SkillCategory.HP_POT -> RoseAlert
    SkillCategory.MP_POT -> ElectricCyan
    SkillCategory.FP_POT -> EmeraldRun
    SkillCategory.ATTACK_AOE -> AmberLogic
    SkillCategory.COOLDOWN_SKILL -> AmberLogic
    SkillCategory.UTILITY -> TextSecondary
}

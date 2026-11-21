package com.example.flyff.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import android.graphics.BitmapFactory
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import com.example.dualweb.DualLayoutMode
import com.example.flyff.ActiveBuffDefinition
import com.example.flyff.BuffTimerOcrReader
import com.example.flyff.FlyffLabelPresets
import com.example.flyff.LayoutHudCalibration
import com.example.flyff.RectSpec
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
fun FlyffHudCalibrationStep(
    state: FlyffSetupUiState,
    templates: List<TemplateAssetEntity>,
    canLiveCapture: Boolean,
    onSelectLayoutMode: (DualLayoutMode) -> Unit,
    onCaptureMobileHud: (DualLayoutMode) -> Unit,
    onNudgeHud: (DualLayoutMode, WebViewTarget, HudNudgeTarget, dx: Int, dy: Int, dw: Int, dh: Int) -> Unit,
    onReScanBuffGrid: (DualLayoutMode) -> Unit,
    onUpdatePendingBuff: (String, (PendingBuffDraft) -> PendingBuffDraft) -> Unit,
    onCommitPendingBuffs: (DualLayoutMode) -> Unit,
    onDeleteActiveBuff: (String) -> Unit
) {
    val activeLayout = state.selectedHudLayout
    val layoutCal = state.profile.layouts[activeLayout]
    val templateById = remember(templates) { templates.associateBy { it.id } }

    var nudgeOwner by remember(activeLayout) {
        mutableStateOf(
            when (activeLayout) {
                DualLayoutMode.SINGLE_ACCT_2 -> WebViewTarget.ACCOUNT_2
                else -> WebViewTarget.ACCOUNT_1
            }
        )
    }
    var nudgeTarget by remember { mutableStateOf(HudNudgeTarget.HP) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Unified Single & Split Layout Selector + Capture Card
        Surface(
            color = SlateWell,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, EmeraldRun.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Text(
                    text = "STEP 3 · MOBILE UI HUD & ACTIVE BUFF CALIBRATION",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldRun,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Switch Flyff back to Mobile UI. Calibrate 50/50 Split, A1 Full, and/or A2 Full inside this one profile so you can swap views anytime without re-setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                // Layout mode tabs with calibrated checkmarks
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DualLayoutMode.entries.forEach { mode ->
                        val calibrated = state.profile.layouts.containsKey(mode)
                        FilterChip(
                            selected = activeLayout == mode,
                            onClick = { onSelectLayoutMode(mode) },
                            leadingIcon = if (calibrated) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "${mode.shortLabel} calibrated",
                                        tint = EmeraldRun,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            label = { Text(mode.shortLabel, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                                selectedLabelColor = ElectricCyan
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("hud_layout_tab_${mode.name.lowercase()}")
                        )
                    }
                }

                Button(
                    onClick = { onCaptureMobileHud(activeLayout) },
                    enabled = !state.isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldRun,
                        contentColor = Color(0xFF031911)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("capture_mobile_hud_btn")
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (canLiveCapture) {
                            "Capture ${activeLayout.shortLabel} HUD & Active Buffs"
                        } else {
                            "Open Dual Flyff to Capture ${activeLayout.shortLabel}"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Live vital % readouts for calibrated bars
                if (layoutCal != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        layoutCal.charHuds.forEach { (owner, _) ->
                            val r = state.liveVitalReadouts[owner]
                            val ownerAccent = if (owner == WebViewTarget.ACCOUNT_1) ElectricCyan else EmeraldRun
                            Surface(
                                color = SlateSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, ownerAccent.copy(alpha = 0.4f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    Text(
                                        text = "${owner.shortLabel} DETECTED BARS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ownerAccent,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "HP ${r?.hpPct ?: "--"}% · MP ${r?.mpPct ?: "--"}% · FP ${r?.fpPct ?: "--"}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Visual Overlay Preview + Precision Nudge Tuner
        if (layoutCal != null) {
            val previewBmp = state.hudPreviewBitmap?.takeIf {
                !it.isRecycled && state.hudPreviewLayout == activeLayout
            }
            if (previewBmp != null) {
                HudOverlayPreviewCard(
                    bitmap = previewBmp,
                    calibration = layoutCal
                )
            }

            // Nudge / Fine-Tune Controls
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                            Text(
                                text = "FINE-TUNE BAR / BUFF BOX (${activeLayout.shortLabel})",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (previewBmp != null) {
                            TextButton(
                                onClick = { onReScanBuffGrid(activeLayout) },
                                modifier = Modifier.testTag("rescan_buff_grid_btn")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = VioletVar, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Re-Scan Buffs", color = VioletVar)
                            }
                        }
                    }

                    // Owner toggle if both A1 and A2 calibrated in this layout
                    if (layoutCal.charHuds.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            layoutCal.charHuds.keys.forEach { owner ->
                                FilterChip(
                                    selected = nudgeOwner == owner,
                                    onClick = { nudgeOwner = owner },
                                    label = { Text(owner.displayLabel) },
                                    modifier = Modifier.weight(1f).height(44.dp)
                                )
                            }
                        }
                    } else if (layoutCal.charHuds.size == 1) {
                        nudgeOwner = layoutCal.charHuds.keys.first()
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        HudNudgeTarget.entries.forEach { tgt ->
                            FilterChip(
                                selected = nudgeTarget == tgt,
                                onClick = { nudgeTarget = tgt },
                                label = { Text(tgt.label, maxLines = 1) },
                                modifier = Modifier.weight(1f).height(44.dp)
                            )
                        }
                    }

                    // Position & Size Nudge Buttons
                    val stepPx = 6
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NudgeBtn("◀ X", Modifier.weight(1f)) { onNudgeHud(activeLayout, nudgeOwner, nudgeTarget, -stepPx, 0, 0, 0) }
                        NudgeBtn("X ▶", Modifier.weight(1f)) { onNudgeHud(activeLayout, nudgeOwner, nudgeTarget, stepPx, 0, 0, 0) }
                        NudgeBtn("▲ Y", Modifier.weight(1f)) { onNudgeHud(activeLayout, nudgeOwner, nudgeTarget, 0, -stepPx, 0, 0) }
                        NudgeBtn("Y ▼", Modifier.weight(1f)) { onNudgeHud(activeLayout, nudgeOwner, nudgeTarget, 0, stepPx, 0, 0) }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NudgeBtn("-W", Modifier.weight(1f)) { onNudgeHud(activeLayout, nudgeOwner, nudgeTarget, 0, 0, -stepPx, 0) }
                        NudgeBtn("+W", Modifier.weight(1f)) { onNudgeHud(activeLayout, nudgeOwner, nudgeTarget, 0, 0, stepPx, 0) }
                        NudgeBtn("-H", Modifier.weight(1f)) { onNudgeHud(activeLayout, nudgeOwner, nudgeTarget, 0, 0, 0, -stepPx) }
                        NudgeBtn("+H", Modifier.weight(1f)) { onNudgeHud(activeLayout, nudgeOwner, nudgeTarget, 0, 0, 0, stepPx) }
                    }
                }
            }
        }

        // Auto-Cropped Active Buff Labeling Deck
        if (state.pendingBuffs.isNotEmpty()) {
            Surface(
                color = SlateSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, VioletVar.copy(alpha = 0.55f)),
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
                                text = "DETECTED ACTIVE BUFFS (${state.pendingBuffs.count { it.include }}/${state.pendingBuffs.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = VioletVar,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Top 62% artwork is saved for matching; bottom timer strip is read live via OCR.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Button(
                            onClick = { onCommitPendingBuffs(activeLayout) },
                            enabled = !state.isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = VioletVar, contentColor = Color.White),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("commit_active_buffs_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save Buffs", fontWeight = FontWeight.Bold)
                        }
                    }

                    state.pendingBuffs.forEach { buff ->
                        PendingBuffCard(
                            buff = buff,
                            onUpdate = { transform -> onUpdatePendingBuff(buff.tempId, transform) }
                        )
                    }
                }
            }
        }

        // Saved Active Buff Icons Roster
        SavedActiveBuffsRoster(
            activeBuffs = state.profile.activeBuffs,
            templateById = templateById,
            onDelete = onDeleteActiveBuff
        )
    }
}

@Composable
private fun HudOverlayPreviewCard(
    bitmap: android.graphics.Bitmap,
    calibration: LayoutHudCalibration
) {
    val imgBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val aspect = (bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()).coerceIn(0.45f, 2.2f)

    Surface(
        color = SlateWell,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "CALIBRATED HUD OVERLAY (${calibration.layoutMode.shortLabel})",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "HP=Red · MP=Cyan · FP=Green · Buffs=Violet",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianBg),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                ) {
                    Image(
                        bitmap = imgBitmap,
                        contentDescription = "Captured Mobile HUD preview",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        fun drawSpec(spec: RectSpec?, color: Color, strokeWidth: Float = 3f) {
                            if (spec == null || !spec.isValid) return
                            val l = spec.normLeft * size.width
                            val t = spec.normTop * size.height
                            val w = (spec.normRight - spec.normLeft) * size.width
                            val h = (spec.normBottom - spec.normTop) * size.height
                            if (w > 1f && h > 1f) {
                                drawRect(
                                    color = color,
                                    topLeft = Offset(l, t),
                                    size = Size(w, h),
                                    style = Stroke(width = strokeWidth)
                                )
                            }
                        }

                        calibration.charHuds.values.forEach { hud ->
                            drawSpec(hud.hpBar?.rect, RoseAlert, 3.5f)
                            drawSpec(hud.mpBar?.rect, ElectricCyan, 3.5f)
                            drawSpec(hud.fpBar?.rect, EmeraldRun, 3.5f)
                            drawSpec(hud.buffGridRegion, VioletVar, 3f)
                        }
                        drawSpec(calibration.attackToggleRegion, AmberLogic, 3.5f)
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingBuffCard(
    buff: PendingBuffDraft,
    onUpdate: ((PendingBuffDraft) -> PendingBuffDraft) -> Unit
) {
    val ownerAccent = if (buff.owner == WebViewTarget.ACCOUNT_1) ElectricCyan else EmeraldRun
    val preview = remember(buff.fullTileBitmap) { buff.fullTileBitmap.asImageBitmap() }
    val timerBadge = if (buff.detectedTimerSec != null) {
        BuffTimerOcrReader.formatSecondsShort(buff.detectedTimerSec)
    } else if (buff.detectedTimerRaw.isNotBlank()) {
        buff.detectedTimerRaw
    } else {
        "No timer"
    }

    Surface(
        color = SlateWell,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (buff.include) VioletVar.copy(alpha = 0.5f) else HairlineBorder),
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
                    checked = buff.include,
                    onCheckedChange = { c -> onUpdate { it.copy(include = c) } },
                    colors = CheckboxDefaults.colors(checkedColor = VioletVar, checkmarkColor = Color.White),
                    modifier = Modifier.testTag("pending_buff_check_${buff.tempId}")
                )
                Image(
                    bitmap = preview,
                    contentDescription = "Detected active buff icon",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ObsidianBg)
                        .border(1.dp, ownerAccent, RoundedCornerShape(8.dp))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${buff.owner.shortLabel} · R${buff.row + 1}C${buff.col + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ownerAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AmberLogic.copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Timer, contentDescription = null, tint = AmberLogic, modifier = Modifier.size(12.dp))
                            Text(
                                text = timerBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberLogic,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    OutlinedTextField(
                        value = buff.label,
                        onValueChange = { v -> onUpdate { it.copy(label = v) } },
                        singleLine = true,
                        label = { Text("Active Buff Name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pending_buff_label_${buff.tempId}")
                    )
                }
            }

            if (buff.include) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    FlyffLabelPresets.quickBuffLabels.forEach { preset ->
                        Surface(
                            onClick = { onUpdate { it.copy(label = preset) } },
                            color = SlateElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (buff.label.equals(preset, true)) VioletVar else HairlineBorder
                            )
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (buff.label.equals(preset, true)) VioletVar else TextSecondary,
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
private fun SavedActiveBuffsRoster(
    activeBuffs: List<ActiveBuffDefinition>,
    templateById: Map<String, TemplateAssetEntity>,
    onDelete: (String) -> Unit
) {
    Surface(
        color = SlateSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, VioletVar.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "SAVED ACTIVE BUFF ICONS (${activeBuffs.size})",
                style = MaterialTheme.typography.titleSmall,
                color = VioletVar,
                fontWeight = FontWeight.Bold
            )
            if (activeBuffs.isEmpty()) {
                Text(
                    text = "No active buff icons saved yet. Cast your buffs in-game, then tap Capture HUD & Active Buffs above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim
                )
            } else {
                activeBuffs.forEach { buff ->
                    val tpl = (buff.previewTemplateId ?: buff.perLayoutTemplateIds.values.firstOrNull())
                        ?.let { templateById[it] }
                    val ownerAccent = if (buff.targetChar == WebViewTarget.ACCOUNT_1) ElectricCyan else EmeraldRun
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
                                contentDescription = buff.label,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buff.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${buff.targetChar.shortLabel} · Layouts: ${buff.perLayoutTemplateIds.keys.joinToString(", ") { it.shortLabel }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ownerAccent
                            )
                        }
                        IconButton(
                            onClick = { onDelete(buff.id) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("delete_active_buff_${buff.id}")
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${buff.label}", tint = RoseAlert, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactTemplateIcon(
    imagePath: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, imagePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull()
        }
    }
    val loaded = bmp
    if (loaded != null && !loaded.isRecycled) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.background(ObsidianBg)
        )
    } else {
        Box(modifier = modifier.background(SlateElevated))
    }
}

@Composable
private fun NudgeBtn(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, HairlineBorder),
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        modifier = modifier.height(44.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextPrimary, maxLines = 1)
    }
}

package com.example.flyff.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.flyff.FlyffCatalogEntry
import com.example.flyff.FlyffCatalogState
import com.example.flyff.FlyffSkillGroup
import com.example.ui.theme.AmberLogic
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldRun
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateWell
import com.example.ui.theme.TextDim
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletVar

/**
 * Organized, searchable popup dialog displaying the full Flyff Universe Buff, Skill & Consumable
 * catalog (with cached official Flyff skill icons when synced) grouped by class/role.
 */
@Composable
fun FlyffCatalogPickerDialog(
    title: String,
    catalogState: FlyffCatalogState,
    buffsOnlyDefault: Boolean,
    currentLabel: String,
    onRefreshCatalog: () -> Unit,
    onSelect: (FlyffCatalogEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<FlyffSkillGroup?>(null) }
    var onlyBuffs by remember { mutableStateOf(buffsOnlyDefault) }

    val filtered = remember(catalogState.entries, query, selectedGroup, onlyBuffs) {
        val q = query.trim().lowercase()
        catalogState.entries.filter { entry ->
            val matchesGroup = selectedGroup == null || entry.group == selectedGroup
            val matchesBuff = !onlyBuffs || entry.isBuff || entry.group == FlyffSkillGroup.CONSUMABLES
            val matchesQuery = q.isBlank() ||
                entry.name.lowercase().contains(q) ||
                entry.group.label.lowercase().contains(q) ||
                entry.recommendedCategory.shortLabel.lowercase().contains(q)
            matchesGroup && matchesBuff && matchesQuery
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SlateSurface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.82f)
                .testTag("flyff_catalog_picker_dialog")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlateWell)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${catalogState.entries.size} skills · ${catalogState.syncedIconCount} icons" +
                                if (catalogState.isSyncing) " (syncing...)" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (catalogState.syncedIconCount > 0) EmeraldRun else TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    IconButton(
                        onClick = onRefreshCatalog,
                        enabled = !catalogState.isSyncing,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync official Flyff icons",
                            tint = ElectricCyan
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close picker", tint = TextSecondary)
                    }
                }

                // Search + Buffs-only toggle
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                        placeholder = { Text("Search buff, skill, class, or item...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("catalog_picker_search")
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = onlyBuffs,
                            onClick = { onlyBuffs = !onlyBuffs },
                            label = { Text("Buffs Only") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VioletVar.copy(alpha = 0.22f),
                                selectedLabelColor = VioletVar
                            ),
                            modifier = Modifier.height(40.dp)
                        )
                        FilterChip(
                            selected = selectedGroup == null,
                            onClick = { selectedGroup = null },
                            label = { Text("All Classes") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan.copy(alpha = 0.20f),
                                selectedLabelColor = ElectricCyan
                            ),
                            modifier = Modifier.height(40.dp)
                        )
                        FlyffSkillGroup.entries.forEach { grp ->
                            FilterChip(
                                selected = selectedGroup == grp,
                                onClick = { selectedGroup = if (selectedGroup == grp) null else grp },
                                label = { Text(grp.shortLabel, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan.copy(alpha = 0.20f),
                                    selectedLabelColor = ElectricCyan
                                ),
                                modifier = Modifier.height(40.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = HairlineBorder)

                // Filtered list
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No skills matched \"$query\". Try clearing filters or type a custom name.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextDim
                                )
                            }
                        }
                    } else {
                        items(filtered, key = { it.id }) { entry ->
                            val isSelected = entry.name.equals(currentLabel.trim(), ignoreCase = true)
                            Surface(
                                color = if (isSelected) ElectricCyan.copy(alpha = 0.14f) else SlateWell,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) ElectricCyan else HairlineBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(entry) }
                                    .testTag("catalog_entry_${entry.id}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    if (!entry.cachedIconPath.isNullOrBlank()) {
                                        CompactTemplateIcon(
                                            imagePath = entry.cachedIconPath,
                                            contentDescription = entry.name,
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .border(1.dp, HairlineBorder, RoundedCornerShape(6.dp))
                                        )
                                    } else {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ObsidianBg)
                                                .border(1.dp, HairlineBorder, RoundedCornerShape(6.dp))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = if (entry.isBuff) VioletVar else ElectricCyan,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${entry.group.shortLabel} · ${entry.recommendedCategory.shortLabel}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (entry.isBuff) VioletVar else TextSecondary
                                        )
                                    }

                                    if (entry.isBuff) {
                                        Text(
                                            text = "BUFF",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AmberLogic,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(AmberLogic.copy(alpha = 0.14f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = HairlineBorder)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlateWell)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = TextSecondary)
                    }
                }
            }
        }
    }
}

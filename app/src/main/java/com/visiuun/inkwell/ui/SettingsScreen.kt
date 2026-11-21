package com.visiuun.inkwell.ui

import android.content.Intent
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.visiuun.inkwell.BuildConfig
import com.visiuun.inkwell.data.Settings
import com.visiuun.inkwell.data.ThemeMode
import com.visiuun.inkwell.image.Scan
import com.visiuun.inkwell.nfc.CaseInfo
import com.visiuun.inkwell.ui.theme.Sym
import com.visiuun.inkwell.update.Updater

private class SettingItem(
    val icon: ImageVector,
    val shape: RoundedPolygon,
    val title: String,
    val body: String?,
    val checked: Boolean? = null,
    val onClick: (() -> Unit)? = null,
    val accent: Boolean = false,
)

@Composable
fun SettingsScreen(
    settings: Settings,
    case: CaseInfo?,
    designCount: Int,
    report: String,
    vm: InkwellViewModel,
    openPage: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val haptics = LocalHaptics.current
    val update: ((Settings) -> Settings) -> Unit = {
        vm.updateSettings(it)
        haptics.tick()
    }
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { ScreenTitle("Settings", Modifier.widthIn(max = 640.dp)) }

        item { SectionHeader("Your case", Modifier.widthIn(max = 640.dp)) }
        item {
            val rows = buildList {
                if (case == null) {
                    add(SettingItem(Sym.Smartphone, MaterialShapes.Gem, "Not met yet",
                        "Inkwell assumes the common 240 × 416 four-colour panel until it has talked to your case", accent = true))
                } else {
                    add(SettingItem(Sym.AspectRatio, MaterialShapes.Gem, "${case.width} × ${case.height} panel",
                        "Black, white, yellow and red", accent = true))
                    if (case.version.isNotEmpty()) {
                        add(SettingItem(Sym.Memory, MaterialShapes.Pentagon, "Firmware ${case.version}", "Maker ${case.manufacturer} · batch ${case.batch}"))
                    }
                    case.serial?.let { add(SettingItem(Sym.Tag, MaterialShapes.Diamond, "Serial $it", null)) }
                }
                add(SettingItem(Sym.Contactless, MaterialShapes.Sunny, "Read case details",
                    "Asks the case for its panel size, firmware and serial number", onClick = vm::readCase))
                add(SettingItem(Sym.InkEraser, MaterialShapes.Arch, "Wipe to white",
                    "Clears the panel. Takes as long as sending a picture.", onClick = vm::wipe))
            }
            Group(rows)
        }

        item { SectionHeader("Picture direction", Modifier.widthIn(max = 640.dp)) }
        item {
            ScanSelector(settings.scan, 0, 4) { scan -> update { it.copy(scan = scan) } }
            Group(offset = 1, rows = listOf(
                SettingItem(Sym.Grain, MaterialShapes.Pentagon, "Send the test card",
                    "A letter F with a red bar on top and yellow down the left. If the panel shows it right, photos will be right too.",
                    onClick = vm::sendTestCard),
                SettingItem(Sym.Rotate180, MaterialShapes.Cookie4Sided, "Turn upside down",
                    "Switch on if pictures arrive on the case head over heels",
                    checked = settings.flip, onClick = { update { it.copy(flip = !it.flip) } }),
                SettingItem(Sym.Flip, MaterialShapes.SemiCircle, "Mirror",
                    "Switch on if pictures arrive left-right reversed",
                    checked = settings.mirror, onClick = { update { it.copy(mirror = !it.mirror) } }),
            ))
        }

        item { SectionHeader("Connection", Modifier.widthIn(max = 640.dp)) }
        item {
            // Refreshed whenever Inkwell comes back to the front, e.g. from the system's app settings.
            val rivals = vm.rivals.collectAsState().value
            val context = LocalContext.current
            Group(buildList {
                add(SettingItem(Sym.Bolt, MaterialShapes.SoftBurst, "Wake NFC automatically",
                    "Android stops looking once it has seen the case. Opening the camera for a blink makes it look again. Nothing is recorded.",
                    checked = settings.nudge, onClick = { update { it.copy(nudge = !it.nudge) } }))
                add(SettingItem(Sym.Contactless, MaterialShapes.Arch, "Keep Android quiet about the case",
                    "Android treats the case as an empty tag and opens its \"New tag scanned\" screen whenever it notices it. Inkwell takes the tag instead and shows nothing.",
                    checked = settings.quiet, onClick = { update { it.copy(quiet = !it.quiet) } }))
                for (rival in rivals) {
                    add(SettingItem(Sym.Warning, MaterialShapes.Pentagon, "Stop the \"Choose an action\" question",
                        "\"${rival.label}\" also wants the case's tag, so when Inkwell is closed Android asks which app to use, every time. " +
                            "Tap here, then choose Disable (or Force stop and Disable). After that the case stays silent.",
                        accent = true,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${rival.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }))
                }
            })
        }

        item { SectionHeader("Library", Modifier.widthIn(max = 640.dp)) }
        item {
            val rows = buildList {
                add(SettingItem(Sym.History, MaterialShapes.Clover4Leaf, "Keep sent designs",
                    "Saves each picture that reaches the case",
                    checked = settings.keepHistory, onClick = { update { it.copy(keepHistory = !it.keepHistory) } }))
                if (designCount > 0) {
                    add(SettingItem(Sym.DeleteSweep, MaterialShapes.Slanted, "Empty the library",
                        if (designCount == 1) "Deletes the saved design" else "Deletes all $designCount saved designs",
                        onClick = vm::clearLibrary))
                }
            }
            Group(rows)
        }

        item { SectionHeader("Appearance", Modifier.widthIn(max = 640.dp)) }
        item {
            ThemeSelector(settings.theme, 0, 3) { t -> update { it.copy(theme = t) } }
            Group(
                listOf(
                    SettingItem(Sym.Contrast, MaterialShapes.Circle, "OLED black",
                        "Pure black backgrounds in dark theme",
                        checked = settings.oled, onClick = { update { it.copy(oled = !it.oled) } }),
                    SettingItem(Sym.Vibration, MaterialShapes.Clover4Leaf, "Haptics",
                        "Light taps as the picture goes across, and a firmer one when it lands",
                        checked = settings.haptics, onClick = { update { it.copy(haptics = !it.haptics) } }),
                ),
                offset = 1,
            )
        }

        item { SectionHeader("Updates", Modifier.widthIn(max = 640.dp)) }
        item {
            UpdateSection(settings.autoUpdate, { on -> vm.updateSettings { it.copy(autoUpdate = on) } }, openPage)
        }

        item { SectionHeader("Troubleshooting", Modifier.widthIn(max = 640.dp)) }
        item {
            Group(listOf(
                SettingItem(Sym.Tune, MaterialShapes.Slanted, "Copy case report", report,
                    onClick = { clipboard?.setPrimaryClip(ClipData.newPlainText("Inkwell case report", report)) }),
            ))
        }

        item { SectionHeader("About", Modifier.widthIn(max = 640.dp)) }
        item {
            Group(listOf(
                SettingItem(Sym.Info, MaterialShapes.Oval, "Inkwell ${BuildConfig.VERSION_NAME}",
                    "An independent app for the passive NFC four-colour e-ink cases sold with the SpaceInk and DeerPhoneCase apps. No account, no ads, no tracking. It only goes online to look for its own updates."),
                SettingItem(Sym.OpenInNew, MaterialShapes.Diamond, "Source on GitHub",
                    "github.com/${BuildConfig.UPDATE_REPO}", onClick = { openPage(Updater.REPO_URL) }),
            ))
        }
    }
}

@Composable
private fun ThemeSelector(selected: ThemeMode, index: Int, count: Int, onSelect: (ThemeMode) -> Unit) {
    val options = ThemeMode.entries
    ListItem(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(bottom = ListItemDefaults.SegmentedGap),
        leadingContent = {
            ShapeBadge(Sym.BrightnessMedium, MaterialShapes.Pill)
        },
        supportingContent = {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                options.forEachIndexed { i, option ->
                    ToggleButton(
                        checked = option == selected,
                        onCheckedChange = { onSelect(option) },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        colors = ToggleButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(option.name.lowercase().replaceFirstChar { it.uppercase() }, maxLines = 1)
                    }
                }
            }
        },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Text("Theme") }
}

@Composable
private fun ScanSelector(selected: Scan, index: Int, count: Int, onSelect: (Scan) -> Unit) {
    val options = Scan.entries
    ListItem(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(bottom = ListItemDefaults.SegmentedGap),
        leadingContent = { ShapeBadge(Sym.AspectRatio, MaterialShapes.Diamond) },
        supportingContent = {
            Column {
                Text("Rows is right for the cases tested so far. Switch to Columns only if pictures arrive as streaks.")
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    options.forEachIndexed { i, option ->
                        ToggleButton(
                            checked = option == selected,
                            onCheckedChange = { onSelect(option) },
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            colors = ToggleButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text(option.label, maxLines = 1) }
                    }
                }
            }
        },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Text("Pixel order") }
}

@Composable
private fun Group(rows: List<SettingItem>, offset: Int = 0) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { i, row -> SettingRow(row, i + offset, rows.size + offset) }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingRow(row: SettingItem, index: Int, count: Int) {
    val click = row.onClick
    val cs = MaterialTheme.colorScheme
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val colors = ListItemDefaults.segmentedColors(containerColor = cs.surfaceContainer)
    val leading: @Composable () -> Unit = {
        if (row.accent) ShapeBadge(row.icon, row.shape, container = cs.primaryContainer, content = cs.onPrimaryContainer)
        else ShapeBadge(row.icon, row.shape)
    }
    val supporting: (@Composable () -> Unit)? = row.body?.let { { Text(it) } }
    when {
        row.checked != null -> ListItem(
            checked = row.checked,
            onCheckedChange = { click?.invoke() },
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = { Switch(checked = row.checked, onCheckedChange = null) },
            shapes = shapes,
            colors = colors,
        ) { Text(row.title) }
        click != null -> ListItem(
            onClick = click,
            leadingContent = leading,
            supportingContent = supporting,
            shapes = shapes,
            colors = colors,
        ) { Text(row.title) }
        else -> ListItem(
            leadingContent = leading,
            supportingContent = supporting,
            shapes = shapes,
            colors = colors,
        ) { Text(row.title) }
    }
}

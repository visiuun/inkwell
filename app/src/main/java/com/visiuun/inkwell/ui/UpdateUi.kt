package com.visiuun.inkwell.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.visiuun.inkwell.ui.theme.Sym
import com.visiuun.inkwell.update.UpdateState
import com.visiuun.inkwell.update.Updater

private fun size(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

/** Banner on the Create screen when a newer release is on GitHub. */
@Composable
fun UpdateCard(openPage: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val rel = Updater.latest.value ?: return
    Surface(shape = RoundedCornerShape(32.dp), color = cs.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeBadge(Sym.Download, MaterialShapes.Pentagon, size = 52.dp, container = cs.primaryContainer, content = cs.onPrimaryContainer)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Update available", style = MaterialTheme.typography.titleLargeEmphasized, color = cs.onSecondaryContainer)
                    Text(
                        "Inkwell ${rel.version} · you have ${Updater.currentVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            UpdateAction(openPage)
        }
    }
}

@Composable
private fun UpdateAction(openPage: (String) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHaptics.current
    val rel = Updater.latest.value
    when (Updater.state.value) {
        UpdateState.DOWNLOADING -> {
            Text("Downloading… ${(Updater.progress.floatValue * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(
                progress = { Updater.progress.floatValue },
                modifier = Modifier.fillMaxWidth(),
                color = cs.primary,
                trackColor = cs.surfaceContainerHighest,
            )
        }
        UpdateState.INSTALLING -> Text("Confirm the install in the system dialog.", style = MaterialTheme.typography.bodyMedium)
        else -> if (rel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        haptics.click()
                        Updater.install(context)
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer),
                ) { Text("Update now" + if (rel.apkSize > 0) " (${size(rel.apkSize)})" else "") }
                if (rel.page.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = { openPage(rel.page) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.height(52.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.surfaceContainerHighest, contentColor = cs.onSurface),
                    ) { Text("What's new") }
                }
            }
        }
    }
    if (Updater.message.value.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(Updater.message.value, style = MaterialTheme.typography.bodySmall, color = cs.error)
    }
}

/** The "Updates" rows in Settings. */
@Composable
fun UpdateSection(autoUpdate: Boolean, setAutoUpdate: (Boolean) -> Unit, openPage: (String) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHaptics.current
    val state = Updater.state.value
    var asked by remember { mutableStateOf(0) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked++ }
    // Reading `asked` re-evaluates the permission after the prompt closes.
    val needsNotify = asked >= 0 && Build.VERSION.SDK_INT >= 33 && autoUpdate &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val count = if (needsNotify) 3 else 2
    val colors = ListItemDefaults.segmentedColors(containerColor = cs.surfaceContainer)

    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        ListItem(
            leadingContent = { ShapeBadge(Sym.Download, MaterialShapes.Pentagon, container = cs.primaryContainer, content = cs.onPrimaryContainer) },
            supportingContent = {
                Column {
                    Text("Installed: Inkwell ${Updater.currentVersion}")
                    if (Updater.pending) {
                        Spacer(Modifier.height(12.dp))
                        UpdateAction(openPage)
                    } else if (state == UpdateState.ERROR && Updater.message.value.isNotEmpty()) {
                        Text(Updater.message.value, color = cs.error)
                    }
                }
            },
            trailingContent = {
                when (state) {
                    UpdateState.CHECKING -> LoadingIndicator(Modifier.size(36.dp))
                    UpdateState.DOWNLOADING, UpdateState.INSTALLING -> {}
                    else -> TextButton(onClick = {
                        haptics.click()
                        Updater.check()
                    }) { Text("Check now") }
                }
            },
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = count),
            colors = colors,
        ) {
            Text(
                when (state) {
                    UpdateState.AVAILABLE, UpdateState.DOWNLOADING, UpdateState.INSTALLING -> "Inkwell ${Updater.latest.value?.version} is available"
                    UpdateState.UP_TO_DATE -> "You're up to date"
                    UpdateState.CHECKING -> "Checking for updates…"
                    UpdateState.ERROR -> "Couldn't check for updates"
                    UpdateState.IDLE -> "Updates"
                },
            )
        }
        ListItem(
            checked = autoUpdate,
            onCheckedChange = {
                haptics.toggle(it)
                setAutoUpdate(it)
                if (it) Updater.checkIfDue(true)
            },
            leadingContent = { ShapeBadge(Sym.Refresh, MaterialShapes.Sunny) },
            supportingContent = { Text("Once a day, from GitHub releases. This is the only thing Inkwell goes online for.") },
            trailingContent = { Switch(checked = autoUpdate, onCheckedChange = null) },
            shapes = ListItemDefaults.segmentedShapes(index = 1, count = count),
            colors = colors,
        ) { Text("Check for updates automatically") }
        if (needsNotify) {
            ListItem(
                onClick = { notify.launch(Manifest.permission.POST_NOTIFICATIONS) },
                leadingContent = { ShapeBadge(Sym.Info, MaterialShapes.Oval) },
                supportingContent = { Text("So a new version can announce itself") },
                shapes = ListItemDefaults.segmentedShapes(index = 2, count = count),
                colors = colors,
            ) { Text("Allow notifications") }
        }
    }
    Spacer(Modifier.height(8.dp))
}

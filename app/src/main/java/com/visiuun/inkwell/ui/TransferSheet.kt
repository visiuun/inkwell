package com.visiuun.inkwell.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.visiuun.inkwell.nfc.CaseInfo
import com.visiuun.inkwell.nfc.RefreshResult
import com.visiuun.inkwell.ui.theme.Sym
import kotlinx.coroutines.delay

class TransferActions(
    val dismiss: () -> Unit,
    val retry: () -> Unit,
    val allowNudge: () -> Unit,
    val openNfcSettings: () -> Unit,
)

/** Everything between tapping Send and the picture standing on the case. */
@Composable
fun TransferSheet(transfer: Transfer, actions: TransferActions) {
    if (transfer is Transfer.Idle) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = actions.dismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp).padding(bottom = 20.dp).heightIn(min = 330.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                transfer,
                contentKey = { it::class },
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() },
                label = "transfer",
            ) { state ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (state) {
                        is Transfer.Waiting -> Waiting(state, actions)
                        Transfer.Connecting -> Working("Saying hello", "Unlocking the case and reading its panel size.")
                        is Transfer.Sending -> Sending(state)
                        is Transfer.Refreshing -> Refreshing(state)
                        is Transfer.Done -> Done(state, actions)
                        is Transfer.Failed -> Failed(state, actions)
                        Transfer.Idle -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun Headline(title: String, body: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun QuietButton(text: String, onClick: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.height(52.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(text) }
}

@Composable
private fun Waiting(state: Transfer.Waiting, actions: TransferActions) {
    val cs = MaterialTheme.colorScheme
    ContainedLoadingIndicator(Modifier.size(104.dp), containerColor = cs.primaryContainer, indicatorColor = cs.onPrimaryContainer)
    when (state.hint) {
        WaitHint.SEARCHING -> Headline("Looking for your case", "Keep the phone in its case, or hold its back against the panel.")
        WaitHint.NUDGING -> Headline("Looking for your case", "Waking NFC so it notices the case again.")
        WaitHint.NEEDS_PERMISSION -> Headline(
            "Android needs a nudge",
            "A case never leaves the phone, so Android stops scanning for it. Opening the camera for a blink restarts the scan. Nothing is recorded or saved.",
        )
        WaitHint.RESEAT -> Headline(
            "Still not seeing it",
            "Lift the phone out of the case for five seconds and put it back. Inkwell picks it up the moment it returns.",
        )
    }
    if (state.hint == WaitHint.NEEDS_PERMISSION) {
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = actions.dismiss,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.height(52.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.surfaceContainerHighest, contentColor = cs.onSurface),
            ) { Text("Cancel") }
            Button(
                onClick = actions.allowNudge,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer),
            ) {
                Icon(Sym.Bolt, null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Wake NFC")
            }
        }
    } else {
        QuietButton("Cancel", actions.dismiss)
    }
}

@Composable
private fun Working(title: String, body: String) {
    LoadingIndicator(Modifier.size(104.dp), color = MaterialTheme.colorScheme.primary)
    Headline(title, body)
}

@Composable
private fun Sending(state: Transfer.Sending) {
    val cs = MaterialTheme.colorScheme
    val fraction = if (state.total == 0) 0f else state.sent.toFloat() / state.total
    val shown by animateFloatAsState(fraction, label = "progress")
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.displaySmallEmphasized, color = cs.primary)
    }
    Headline("Sending the picture", "${state.sent} of ${state.total} packets across. Keep the phone still.")
    Spacer(Modifier.height(24.dp))
    LinearWavyProgressIndicator(
        progress = { shown },
        modifier = Modifier.fillMaxWidth().height(14.dp),
        color = cs.primary,
        trackColor = cs.surfaceContainerHighest,
    )
}

@Composable
private fun Refreshing(state: Transfer.Refreshing) {
    val cs = MaterialTheme.colorScheme
    var seconds by remember(state.since) { mutableIntStateOf(0) }
    LaunchedEffect(state.since) {
        while (true) {
            seconds = ((System.currentTimeMillis() - state.since) / 1000).toInt()
            delay(250)
        }
    }
    ContainedLoadingIndicator(Modifier.size(104.dp), containerColor = cs.tertiary, indicatorColor = cs.onTertiary)
    Headline(
        "The panel is redrawing",
        "It flashes through its inks for about twenty seconds, powered by your phone. Don't move it yet.",
    )
    Spacer(Modifier.height(20.dp))
    Pill("$seconds s", icon = Sym.Hourglass)
}

@Composable
private fun Done(state: Transfer.Done, actions: TransferActions) {
    val cs = MaterialTheme.colorScheme
    when (state.result) {
        null -> {
            ResultBadge(Sym.Contactless, cs.primaryContainer, cs.onPrimaryContainer)
            Headline("Case found", describe(state.info))
        }
        RefreshResult.DONE -> if (state.expected != null) {
            Image(
                bitmap = state.expected,
                contentDescription = "The test card",
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .height(260.dp)
                    .aspectRatio(state.expected.width.toFloat() / state.expected.height)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Headline(
                "Does the case match?",
                "It should show exactly this. If it's scrambled, switch the pixel order in Settings and send the card again.",
            )
        } else {
            ResultBadge(Sym.Check, cs.primaryContainer, cs.onPrimaryContainer)
            Headline("On your case", "Done in ${state.seconds} seconds. The picture stays put without power.")
        }
        RefreshResult.UNCONFIRMED -> {
            ResultBadge(Sym.Hourglass, cs.secondaryContainer, cs.onSecondaryContainer)
            Headline(
                "Picture delivered",
                "The case stopped answering before it confirmed the redraw. If the panel is still flashing, leave the phone where it is until it settles.",
            )
        }
        RefreshResult.FAILED -> {
            ResultBadge(Sym.Warning, cs.tertiaryContainer, cs.onTertiaryContainer)
            Headline("The panel didn't finish", "The case got the picture but reported a problem drawing it. Sending again usually fixes it.")
        }
    }
    if (state.result == RefreshResult.FAILED) {
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = actions.dismiss,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.height(52.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.surfaceContainerHighest, contentColor = cs.onSurface),
            ) { Text("Close") }
            Button(onClick = actions.retry, shapes = ButtonDefaults.shapes(), modifier = Modifier.height(52.dp)) { Text("Send again") }
        }
    } else {
        QuietButton("Done", actions.dismiss)
    }
}

private fun describe(info: CaseInfo): String = buildString {
    append("${info.width} × ${info.height} panel")
    if (info.version.isNotEmpty()) append(" · firmware ${info.version}")
    info.serial?.let { append(" · serial $it") }
    append(".")
}

@Composable
private fun Failed(state: Transfer.Failed, actions: TransferActions) {
    val cs = MaterialTheme.colorScheme
    ResultBadge(Sym.Error, cs.tertiaryContainer, cs.onTertiaryContainer)
    Headline(state.title, state.message)
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(
            onClick = actions.dismiss,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.height(52.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.surfaceContainerHighest, contentColor = cs.onSurface),
        ) { Text("Close") }
        if (state.nfcOff) {
            Button(onClick = actions.openNfcSettings, shapes = ButtonDefaults.shapes(), modifier = Modifier.height(52.dp)) { Text("NFC settings") }
        } else {
            Button(onClick = actions.retry, shapes = ButtonDefaults.shapes(), modifier = Modifier.height(52.dp)) { Text("Try again") }
        }
    }
}

@Composable
private fun ResultBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    MorphBadge(from = MaterialShapes.Circle, to = MaterialShapes.Pentagon, morphed = true, container = container, size = 104.dp) {
        Icon(icon, null, Modifier.size(48.dp), tint = content)
    }
}

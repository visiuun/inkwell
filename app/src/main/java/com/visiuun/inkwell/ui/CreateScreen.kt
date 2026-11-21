package com.visiuun.inkwell.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visiuun.inkwell.image.Dither
import com.visiuun.inkwell.image.Ink
import com.visiuun.inkwell.image.Look
import com.visiuun.inkwell.ui.theme.InkColors
import com.visiuun.inkwell.ui.theme.Sym
import com.visiuun.inkwell.update.Updater
import kotlin.math.roundToInt

@Composable
fun CreateScreen(editor: Editor, vm: InkwellViewModel, pick: () -> Unit, openPage: (String) -> Unit, contentPadding: PaddingValues) {
    val haptics = LocalHaptics.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp)) {
            ScreenTitle(
                "Inkwell",
                subtitle = "${editor.width} × ${editor.height} · four inks",
                trailing = { InkRow(Modifier.padding(end = 8.dp), size = 14.dp) },
            )
            if (Updater.pending) {
                UpdateCard(openPage, Modifier.padding(bottom = 12.dp))
            }
            Stage(editor, vm, pick)
            if (editor.hasPhoto) {
                Text(
                    "Drag and pinch the picture to frame it. Double-tap to recentre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }

            SectionHeader("Inks")
            InkToggles(editor.look) {
                haptics.tick()
                vm.setLook(it)
            }

            SectionHeader("Texture")
            DitherPicker(editor.look.dither) { d ->
                haptics.tick()
                vm.setLook { it.copy(dither = d) }
            }
            Text(
                when (editor.look.dither) {
                    Dither.SMOOTH -> "Soft blended dots. The best pick for photos."
                    Dither.CRISP -> "Lighter and punchier, keeps edges sharp."
                    Dither.PATTERN -> "A regular halftone grid with a print feel."
                    Dither.FLAT -> "Solid areas only. Made for logos, text and line art."
                    Dither.SMART -> "Reads the picture and places every pixel to follow its shapes: clean flat areas, " +
                        "sharp lines, truer colour, and dark pictures lifted. Settles a moment after you let go."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
            )

            SectionHeader("Tune", trailing = {
                val base = Look()
                val l = editor.look
                val touched = l.brightness != base.brightness || l.contrast != base.contrast || l.saturation != base.saturation
                TextButton(enabled = touched, onClick = {
                    vm.setLook { it.copy(brightness = base.brightness, contrast = base.contrast, saturation = base.saturation) }
                }) { Text("Reset") }
            })
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Tuner(Sym.Brightness, "Brightness", editor.look.brightness) { v -> vm.setLook { it.copy(brightness = v) } }
                    Tuner(Sym.Contrast, "Contrast", editor.look.contrast) { v -> vm.setLook { it.copy(contrast = v) } }
                    Tuner(Sym.Saturation, "Colour", editor.look.saturation) { v -> vm.setLook { it.copy(saturation = v) } }
                }
            }
        }
    }
}

/** The panel preview with its framing tools beside it. */
@Composable
private fun Stage(editor: Editor, vm: InkwellViewModel, pick: () -> Unit) {
    Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Panel(editor, vm, pick, Modifier.height(372.dp).aspectRatio(editor.width.toFloat() / editor.height))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ToolButton(Sym.AddPhoto, if (editor.hasPhoto) "Change photo" else "Pick a photo", onClick = pick, accent = true)
                ToolToggle(Sym.Fullscreen, "Fill the panel", checked = editor.placement.fill, enabled = editor.hasPhoto) { fill ->
                    vm.setPlacement { it.copy(fill = fill, zoom = 1f, offsetX = 0f, offsetY = 0f) }
                }
                ToolButton(Sym.RotateRight, "Rotate", enabled = editor.hasPhoto) {
                    vm.setPlacement { it.copy(quarterTurns = (it.quarterTurns + 1) % 4, offsetX = 0f, offsetY = 0f) }
                }
                ToolToggle(Sym.InvertColors, "Black backdrop", checked = editor.look.darkBackdrop, enabled = editor.hasPhoto) { dark ->
                    vm.setLook { it.copy(darkBackdrop = dark) }
                }
                ToolButton(Sym.Delete, "Remove photo", enabled = editor.hasPhoto, onClick = vm::removePhoto)
            }
        }
    }
}

@Composable
private fun Panel(editor: Editor, vm: InkwellViewModel, pick: () -> Unit, modifier: Modifier) {
    val backdrop = Color(if (editor.look.darkBackdrop) Ink.BLACK.paper else Ink.WHITE.paper)
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.clip(shape).background(backdrop), contentAlignment = Alignment.Center) {
        AnimatedContent(
            when {
                editor.preview != null -> 2
                editor.loading -> 1
                else -> 0
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "panel",
        ) { state ->
            when (state) {
                2 -> editor.preview?.let { preview ->
                    Image(
                        bitmap = preview,
                        contentDescription = "Preview of the picture on the case",
                        contentScale = ContentScale.FillBounds,
                        // Each ink pixel stays a hard square, the way the panel shows it.
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = {
                                    vm.setPlacement { it.copy(zoom = 1f, offsetX = 0f, offsetY = 0f) }
                                })
                            }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    vm.gesture(pan.x / size.width, pan.y / size.height, zoom)
                                }
                            },
                    )
                }
                1 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(56.dp), color = InkColors.Black)
                }
                else -> Column(
                    Modifier.fillMaxSize().clickable(onClickLabel = "Pick a photo", onClick = pick).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ShapeBadge(Sym.AddPhoto, MaterialShapes.Gem, size = 84.dp, container = InkColors.Yellow, content = InkColors.Black)
                    Spacer(Modifier.height(18.dp))
                    Text("Pick a photo", style = MaterialTheme.typography.titleMediumEmphasized, color = InkColors.Black)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "It shows up here exactly as the case will draw it",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkColors.Black.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        enabled = enabled,
        colors = if (accent) IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer)
        else IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.surfaceContainerHighest, contentColor = cs.onSurface),
        modifier = Modifier.size(52.dp),
    ) { Icon(icon, label) }
}

@Composable
private fun ToolToggle(icon: ImageVector, label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    FilledTonalIconToggleButton(
        checked = checked,
        onCheckedChange = onChange,
        shapes = IconButtonDefaults.toggleableShapes(),
        enabled = enabled,
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = cs.surfaceContainerHighest,
            contentColor = cs.onSurface,
            checkedContainerColor = cs.secondary,
            checkedContentColor = cs.onSecondary,
        ),
        modifier = Modifier.size(52.dp),
    ) { Icon(icon, label) }
}

@Composable
private fun InkToggles(look: Look, set: ((Look) -> Look) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        InkToggle("Yellow", InkColors.Yellow, look.yellow, ButtonGroupDefaults.connectedLeadingButtonShapes()) { on -> set { it.copy(yellow = on) } }
        InkToggle("Red", InkColors.Red, look.red, ButtonGroupDefaults.connectedTrailingButtonShapes()) { on -> set { it.copy(red = on) } }
    }
    Row(
        Modifier.padding(start = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InkDot(InkColors.Black, size = 12.dp)
        InkDot(InkColors.Paper, size = 12.dp)
        Text("Black and white are always in the mix", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.InkToggle(
    label: String,
    color: Color,
    checked: Boolean,
    shapes: androidx.compose.material3.ToggleButtonShapes,
    onChange: (Boolean) -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.weight(1f).height(56.dp),
        shapes = shapes,
        colors = ToggleButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        InkDot(if (checked) color else color.copy(alpha = 0.3f), size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(if (checked) label else "No ${label.lowercase()}", maxLines = 1)
    }
}

@Composable
private fun DitherPicker(selected: Dither, onSelect: (Dither) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DitherRow(selected, onSelect)
        // The one that works the picture out for itself gets a button of its own.
        ToggleButton(
            checked = selected == Dither.SMART,
            onCheckedChange = { onSelect(Dither.SMART) },
            modifier = Modifier.fillMaxWidth().height(64.dp).semantics { role = Role.RadioButton },
            colors = ToggleButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(if (selected == Dither.SMART) Sym.SparkleFill else Sym.Sparkle, null, Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(Dither.SMART.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@Composable
private fun DitherRow(selected: Dither, onSelect: (Dither) -> Unit) {
    val options = listOf(Dither.SMOOTH, Dither.CRISP, Dither.PATTERN, Dither.FLAT)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                modifier = Modifier.weight(1f).height(52.dp).semantics { role = Role.RadioButton },
                contentPadding = PaddingValues(horizontal = 4.dp),
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

@Composable
private fun Tuner(icon: ImageVector, label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.padding(top = 6.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                val shown = (value * 100).roundToInt()
                Text(
                    if (shown > 0) "+$shown" else "$shown",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val haptics = LocalHaptics.current
            Slider(
                value = value,
                onValueChange = {
                    val stepped = (it * 20).roundToInt() / 20f
                    if (stepped != value) {
                        haptics.tick()
                        onChange(stepped)
                    }
                },
                valueRange = -1f..1f,
            )
        }
    }
}

/** Space the floating bar at the bottom of the screen takes up. */
val FloatingBarClearance: Dp = 104.dp

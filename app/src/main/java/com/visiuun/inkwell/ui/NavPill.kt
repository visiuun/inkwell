package com.visiuun.inkwell.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.visiuun.inkwell.ui.theme.Sym

private class NavTab(val tab: Tab, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val Tabs = listOf(
    NavTab(Tab.CREATE, "Create", Sym.Palette, Sym.PaletteFill),
    NavTab(Tab.LIBRARY, "Library", Sym.Library, Sym.LibraryFill),
    NavTab(Tab.SETTINGS, "Settings", Sym.Settings, Sym.SettingsFill),
)

/** Floating pill navigation: the selected tab grows into an icon + label capsule. */
@Composable
fun NavPill(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tabs.forEach { t -> NavPillItem(t, t.tab == selected) { onSelect(t.tab) } }
        }
    }
}

@Composable
private fun NavPillItem(t: NavTab, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    val container by animateColorAsState(if (selected) cs.primary else Color.Transparent, MaterialTheme.motionScheme.fastEffectsSpec(), label = "nav-bg")
    val content by animateColorAsState(if (selected) cs.onPrimary else cs.onSurfaceVariant, MaterialTheme.motionScheme.fastEffectsSpec(), label = "nav-fg")
    Row(
        Modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) t.selectedIcon else t.icon, if (selected) null else t.label, tint = content)
        AnimatedVisibility(
            selected,
            enter = expandHorizontally(spatial) + fadeIn(),
            exit = shrinkHorizontally(spatial) + fadeOut(),
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(t.label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
            }
        }
    }
}

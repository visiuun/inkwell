package com.visiuun.inkwell.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.visiuun.inkwell.ui.theme.Sym

@Composable
fun LibraryScreen(designs: List<DesignItem>, vm: InkwellViewModel, contentPadding: PaddingValues) {
    var open by remember { mutableStateOf<DesignItem?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenTitle(
                "Library",
                subtitle = when (designs.size) {
                    0 -> "Designs you send are kept here"
                    1 -> "1 design, newest first"
                    else -> "${designs.size} designs, newest first"
                },
            )
        }
        if (designs.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary() }
        }
        items(designs, key = { it.design.id }) { item ->
            Surface(
                onClick = { open = item },
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.animateItem(),
            ) {
                Image(
                    bitmap = item.thumb,
                    contentDescription = "Saved design",
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier
                        .padding(10.dp)
                        .aspectRatio(item.design.width.toFloat() / item.design.height)
                        .clip(RoundedCornerShape(18.dp)),
                )
            }
        }
    }

    open?.let { item ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { open = null }, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    bitmap = item.thumb,
                    contentDescription = "Saved design",
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .height(340.dp)
                        .aspectRatio(item.design.width.toFloat() / item.design.height)
                        .clip(RoundedCornerShape(18.dp)),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${item.design.width} × ${item.design.height}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            open = null
                            vm.delete(item)
                        },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.height(56.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(Sym.Delete, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete")
                    }
                    Button(
                        onClick = {
                            open = null
                            vm.send(item)
                        },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(Sym.Send, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Send to case", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Box(Modifier.fillMaxWidth().padding(top = 56.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
            MorphBadge(
                from = MaterialShapes.Pill,
                to = MaterialShapes.Arch,
                morphed = true,
                container = MaterialTheme.colorScheme.secondaryContainer,
                size = 120.dp,
            ) {
                Icon(Sym.Library, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(24.dp))
            Text("Nothing here yet", style = MaterialTheme.typography.headlineSmallEmphasized)
            Spacer(Modifier.height(8.dp))
            Text(
                "Every picture that reaches your case lands here, so swapping back to a favourite takes two taps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

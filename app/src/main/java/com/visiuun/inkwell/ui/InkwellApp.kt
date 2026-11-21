package com.visiuun.inkwell.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import android.net.Uri
import com.visiuun.inkwell.nfc.RefreshResult
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visiuun.inkwell.nfc.CaseReader
import com.visiuun.inkwell.ui.theme.InkwellTheme
import com.visiuun.inkwell.ui.theme.Sym

@Composable
fun InkwellApp(vm: InkwellViewModel, reader: CaseReader) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val case by vm.case.collectAsStateWithLifecycle()
    val designs by vm.designs.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val snackbar = remember { SnackbarHostState() }
    val haptics = remember(view, settings.haptics) { Haptics(view, settings.haptics) }
    val openPage: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
        }
    }

    // The transfer, felt: a tap when the case answers, ticks as packets go across, a pulse
    // every couple of seconds while the panel redraws, and a distinct ending either way.
    var lastStep by remember { mutableIntStateOf(-1) }
    LaunchedEffect(transfer) {
        when (val t = transfer) {
            Transfer.Connecting -> haptics.step()
            is Transfer.Sending -> {
                val step = if (t.total == 0) 0 else t.sent * 24 / t.total
                if (step != lastStep) {
                    lastStep = step
                    haptics.tick()
                }
            }
            is Transfer.Refreshing -> {
                haptics.step()
                while (true) {
                    delay(2000)
                    haptics.tick()
                }
            }
            is Transfer.Done -> {
                lastStep = -1
                if (t.result == RefreshResult.FAILED) {
                    haptics.failure()
                } else {
                    haptics.success()
                    if (t.result == RefreshResult.DONE) {
                        delay(160)
                        haptics.success()
                    }
                }
            }
            is Transfer.Failed -> {
                lastStep = -1
                haptics.failure()
            }
            is Transfer.Waiting, Transfer.Idle -> lastStep = -1
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::pick) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.nudgeAnswered(granted) }

    // The transfer needs the phone awake: the case draws its power from the NFC field.
    DisposableEffect(transfer.active) {
        view.keepScreenOn = transfer.active
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                UiEvent.Nudge -> reader.nudge()
                is UiEvent.Message -> snackbar.showSnackbar(e.text, withDismissAction = true)
            }
        }
    }

    InkwellTheme(settings) {
        CompositionLocalProvider(LocalHaptics provides haptics) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            BackHandler(tab != Tab.CREATE) { vm.showTab(Tab.CREATE) }
            val bars = WindowInsets.statusBars.asPaddingValues()
            val navigation = WindowInsets.navigationBars.asPaddingValues()
            val contentPadding = PaddingValues(
                top = bars.calculateTopPadding() + 8.dp,
                bottom = navigation.calculateBottomPadding() + FloatingBarClearance,
            )
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    tab,
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally { dir * it / 5 } + fadeIn()) togetherWith (slideOutHorizontally { -dir * it / 5 } + fadeOut())
                    },
                    label = "tab",
                ) { t ->
                    when (t) {
                        Tab.CREATE -> CreateScreen(
                            editor, vm,
                            pick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            openPage = openPage,
                            contentPadding = contentPadding,
                        )
                        Tab.LIBRARY -> LibraryScreen(designs, vm, contentPadding)
                        Tab.SETTINGS -> SettingsScreen(settings, case, designs.size, report, vm, openPage, contentPadding)
                    }
                }

                // Lists scroll up underneath the status bar; without this their text would show
                // through the clock.
                val surface = MaterialTheme.colorScheme.surface
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(bars.calculateTopPadding() + 12.dp)
                        .background(Brush.verticalGradient(0f to surface, 0.75f to surface, 1f to surface.copy(alpha = 0f))),
                )

                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavPill(tab, onSelect = {
                        if (it != tab) haptics.tick()
                        vm.showTab(it)
                    })
                    AnimatedVisibility(
                        visible = tab == Tab.CREATE && editor.hasPhoto,
                        enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                    ) {
                        Surface(
                            onClick = {
                                haptics.click()
                                vm.send()
                            },
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shadowElevation = 6.dp,
                            modifier = Modifier.size(64.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Icon(Sym.Send, "Send to case", Modifier.size(28.dp)) }
                        }
                    }
                }

                SnackbarHost(
                    snackbar,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = FloatingBarClearance),
                )
            }

            TransferSheet(
                transfer,
                TransferActions(
                    dismiss = vm::dismiss,
                    retry = vm::retry,
                    allowNudge = { camera.launch(Manifest.permission.CAMERA) },
                    openNfcSettings = {
                        vm.dismiss()
                        try {
                            context.startActivity(Intent(AndroidSettings.ACTION_NFC_SETTINGS))
                        } catch (_: ActivityNotFoundException) {
                        }
                    },
                ),
            )
        }
        }
    }
}

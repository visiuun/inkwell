package com.visiuun.inkwell.data

import android.content.Context
import androidx.core.content.edit
import com.visiuun.inkwell.image.Dither
import com.visiuun.inkwell.image.Look
import com.visiuun.inkwell.image.Scan
import com.visiuun.inkwell.nfc.CaseInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val oled: Boolean = true,
    /** Corrections for a case whose panel is mounted the other way round. */
    val flip: Boolean = false,
    val mirror: Boolean = false,
    /** Keep a copy of every design that reaches the case. */
    val keepHistory: Boolean = true,
    /** Blip the camera so Android re-scans a case that never leaves the antenna. */
    val nudge: Boolean = true,
    /** Order the pixels are sent in. */
    val scan: Scan = Scan.ROWS,
    /** Look for a newer release on GitHub once a day. */
    val autoUpdate: Boolean = true,
    val haptics: Boolean = true,
)

/** The panel the editor targets until a case says otherwise: the common 3.7" four-colour one. */
val DefaultCase = CaseInfo(
    version = "", versionNumber = null, screenType = 0, width = 240, height = 416,
    mode = 0, manufacturer = 0, batch = 0, radio = 0, flag = 0,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("inkwell", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _case = MutableStateFlow(loadCase())
    /** The last case this phone talked to, or null before the first contact. */
    val case: StateFlow<CaseInfo?> = _case.asStateFlow()

    private fun loadSettings() = Settings(
        theme = sp.getString("theme", null)?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.SYSTEM,
        oled = sp.getBoolean("oled", true),
        flip = sp.getBoolean("flip", false),
        mirror = sp.getBoolean("mirror", false),
        keepHistory = sp.getBoolean("history", true),
        nudge = sp.getBoolean("nudge", true),
        // "AUTO" from 1.0.1 followed the vendor app's choice, which was wrong: it now means rows.
        scan = sp.getString("scan", null)?.let { v -> Scan.entries.firstOrNull { it.name == v } } ?: Scan.ROWS,
        autoUpdate = sp.getBoolean("auto_update", true),
        haptics = sp.getBoolean("haptics", true),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        _settings.value = s
        sp.edit {
            putString("theme", s.theme.name)
            putBoolean("oled", s.oled)
            putBoolean("flip", s.flip)
            putBoolean("mirror", s.mirror)
            putBoolean("history", s.keepHistory)
            putBoolean("nudge", s.nudge)
            putString("scan", s.scan.name)
            putBoolean("auto_update", s.autoUpdate)
            putBoolean("haptics", s.haptics)
        }
    }

    private fun loadCase(): CaseInfo? {
        val w = sp.getInt("case_w", 0)
        val h = sp.getInt("case_h", 0)
        if (w <= 0 || h <= 0) return null
        return CaseInfo(
            version = sp.getString("case_version", "") ?: "",
            versionNumber = sp.getInt("case_build", -1).takeIf { it >= 0 },
            screenType = sp.getInt("case_type", 0),
            width = w,
            height = h,
            mode = sp.getInt("case_mode", 0),
            manufacturer = sp.getInt("case_maker", 0),
            batch = sp.getInt("case_batch", 0),
            radio = sp.getInt("case_radio", 0),
            flag = sp.getInt("case_flag", 0),
            serial = sp.getString("case_serial", null),
            raw = sp.getString("case_raw", "") ?: "",
        )
    }

    fun saveCase(info: CaseInfo) {
        // A plain push doesn't ask for the serial; keep the one read earlier from the same case.
        val merged = if (info.serial == null) info.copy(serial = _case.value?.serial) else info
        _case.value = merged
        sp.edit {
            putInt("case_w", merged.width)
            putInt("case_h", merged.height)
            putString("case_version", merged.version)
            putInt("case_build", merged.versionNumber ?: -1)
            putInt("case_type", merged.screenType)
            putInt("case_mode", merged.mode)
            putInt("case_maker", merged.manufacturer)
            putInt("case_batch", merged.batch)
            putInt("case_radio", merged.radio)
            putInt("case_flag", merged.flag)
            putString("case_serial", merged.serial)
            putString("case_raw", merged.raw)
        }
    }

    fun loadLook(): Look = Look(
        brightness = sp.getFloat("look_brightness", 0f),
        contrast = sp.getFloat("look_contrast", 0.15f),
        saturation = sp.getFloat("look_saturation", 0.3f),
        dither = sp.getString("look_dither", null)?.let { v -> Dither.entries.firstOrNull { it.name == v } } ?: Dither.SMOOTH,
        red = sp.getBoolean("look_red", true),
        yellow = sp.getBoolean("look_yellow", true),
        darkBackdrop = sp.getBoolean("look_dark", false),
    )

    fun saveLook(look: Look) = sp.edit {
        putFloat("look_brightness", look.brightness)
        putFloat("look_contrast", look.contrast)
        putFloat("look_saturation", look.saturation)
        putString("look_dither", look.dither.name)
        putBoolean("look_red", look.red)
        putBoolean("look_yellow", look.yellow)
        putBoolean("look_dark", look.darkBackdrop)
    }

    /** What happened on the last transfer, kept for the troubleshooting report. */
    var lastTransfer: String
        get() = sp.getString("last_transfer", "") ?: ""
        set(v) = sp.edit { putString("last_transfer", v) }
}

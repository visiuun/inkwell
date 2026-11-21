package com.visiuun.inkwell.nfc

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Android-side plumbing for finding the case.
 *
 * A phone case never leaves the antenna, and Android only announces a tag when it arrives. So
 * besides plain reader mode this offers [nudge]: opening the camera for a moment makes the NFC
 * stack pause and restart its scan, which announces the case again. Nothing is captured; the
 * camera is opened without any output and closed straight away.
 */
class CaseReader(private val activity: Activity) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var dispatching = false

    val supported: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun listen(onTag: (Tag) -> Unit) {
        val a = adapter ?: return
        val extras = Bundle().apply {
            // The default 125 ms presence check would land between our own frames.
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 2000)
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        try {
            a.enableReaderMode(activity, { tag -> onTag(tag) }, flags, extras)
            listening = true
        } catch (_: Exception) {
        }
        // Reader mode alone is not enough on every phone: after NFC restarts its scan (which is
        // what the camera nudge provokes) some hand the tag to the normal app chooser instead of
        // to the reader. Foreground dispatch outranks that chooser, so with both in place nothing
        // of Android's can pop up over Inkwell. The tag then arrives as an intent.
        try {
            val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags2 = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            a.enableForegroundDispatch(activity, PendingIntent.getActivity(activity, 0, intent, flags2), null, null)
            dispatching = true
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (dispatching) {
            dispatching = false
            try {
                adapter?.disableForegroundDispatch(activity)
            } catch (_: Exception) {
            }
        }
        if (!listening) return
        listening = false
        try {
            adapter?.disableReaderMode(activity)
        } catch (_: Exception) {
        }
    }

    /** Makes Android scan for the case again. Returns false if the camera can't be used for it. */
    fun nudge(): Boolean {
        if (!canNudge(activity)) return false
        return try {
            val cameras = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameras.cameraIdList.firstOrNull() ?: return false
            cameras.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        handler.postDelayed({ runCatching { camera.close() } }, 350)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        runCatching { camera.close() }
                    }
                },
                handler,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun canNudge(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
}

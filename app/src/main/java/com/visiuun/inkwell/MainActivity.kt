package com.visiuun.inkwell

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.visiuun.inkwell.nfc.CaseReader
import com.visiuun.inkwell.ui.InkwellApp
import com.visiuun.inkwell.ui.InkwellViewModel
import com.visiuun.inkwell.ui.Tab
import com.visiuun.inkwell.update.Updater

class MainActivity : ComponentActivity() {
    private val vm: InkwellViewModel by viewModels()
    private lateinit var reader: CaseReader

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        reader = CaseReader(this)
        if (savedInstanceState == null) handle(intent)
        setContent { InkwellApp(vm, reader) }
    }

    // Reader mode stays on for as long as Inkwell is in front. That keeps Android's own
    // "New tag scanned" screen away and hands the case's tag to the app the moment it is seen.
    override fun onResume() {
        super.onResume()
        reader.listen(vm::onTag)
        vm.resumed()
        Updater.init(this)
        Updater.checkIfDue(vm.settings.value.autoUpdate)
    }

    override fun onPause() {
        super.onPause()
        reader.stop()
        vm.paused()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A picture shared to Inkwell or opened with it goes straight into the editor. */
    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.action == Updater.ACTION_SHOW) {
            // Tapped the "update available" notification.
            vm.showTab(Tab.SETTINGS)
            return
        }
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> stream(intent) ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let(vm::pick)
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
}

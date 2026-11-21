package com.visiuun.inkwell.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import org.xmlpull.v1.XmlPullParser

/**
 * Where Android sends the case's tag when Inkwell is not on screen.
 *
 * A case never leaves the phone, and its tag is an empty NFC tag as far as Android can tell. Every
 * time the phone notices it again (after unlocking, or whenever the case's chip resets) Android
 * looks for an app to hand it to, finds none, and opens its own "New tag scanned" screen. This
 * activity claims the tag instead and finishes without ever drawing anything, so nothing appears.
 *
 * Tags that are not the case are passed on to whatever would have handled them otherwise.
 */
class TagSinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (_: Exception) {
            // Whatever happens, never leave an invisible activity in front.
        }
        finish()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        tag ?: return
        if (CaseTags.isCase(this, tag)) {
            // If the app is running and waiting for the case, this is the one it wants.
            CaseTags.listener?.invoke(tag)
        } else {
            passOn(intent)
        }
    }

    /** Gives a tag that isn't ours to the app Android would have fallen back to. */
    private fun passOn(original: Intent) {
        val fallback = packageManager
            .queryIntentActivities(Intent(NfcAdapter.ACTION_TAG_DISCOVERED), PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != packageName } ?: return
        val out = Intent(original).apply {
            action = NfcAdapter.ACTION_TAG_DISCOVERED
            component = ComponentName(fallback.activityInfo.packageName, fallback.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(out)
    }
}

/** Recognises the case's tag and connects the sink to a running app. */
object CaseTags {
    /** Set while the app is alive; called with the case's tag when Android delivers it to the sink. */
    @Volatile var listener: ((Tag) -> Unit)? = null

    private const val PREFS = "inkwell"
    private const val KEY_UID = "case_uid"
    private const val KEY_TECHS = "case_techs"
    /** First byte of every serial number Fudan Microelectronics hands out. */
    private const val FUDAN = 0x1D.toByte()

    private fun hex(id: ByteArray) = id.joinToString("") { "%02X".format(it) }

    /** Remembers the serial of a tag that turned out to be the case, and what kinds of tag it claims to be. */
    fun remember(context: Context, tag: Tag) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UID, hex(tag.id))
            .putString(KEY_TECHS, tag.techList.joinToString(","))
            .apply()
    }

    class Rival(val packageName: String, val label: String)

    /**
     * Apps other than Inkwell that have told Android they handle tags like the case.
     *
     * When Inkwell is not on screen Android offers the case's tag to every such app. With one
     * taker it goes there silently; with more than one Android asks, every single time, and has
     * no "always" to tick. On most phones the only other taker is the system's own "Tags" app,
     * and the question stops for good once that is switched off.
     */
    fun rivals(context: Context): List<Rival> = try {
        val pm = context.packageManager
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TECHS, null)
        val techs = saved?.split(",")?.filter { it.isNotEmpty() }?.toSet()
            ?: setOf("android.nfc.tech.NfcA", "android.nfc.tech.Ndef", "android.nfc.tech.MifareUltralight")
        pm.queryIntentActivities(Intent(NfcAdapter.ACTION_TECH_DISCOVERED), PackageManager.GET_META_DATA)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
            .filter { info ->
                // The app is offered the tag if one of its lists names only kinds the tag has.
                val xml = info.activityInfo.loadXmlMetaData(pm, NfcAdapter.ACTION_TECH_DISCOVERED) ?: return@filter false
                var match = false
                var list: MutableList<String>? = null
                try {
                    var event = xml.eventType
                    while (event != XmlPullParser.END_DOCUMENT && !match) {
                        when (event) {
                            XmlPullParser.START_TAG -> when (xml.name) {
                                "tech-list" -> list = mutableListOf()
                                "tech" -> list?.add(xml.nextText().trim())
                            }
                            XmlPullParser.END_TAG -> if (xml.name == "tech-list") {
                                val wants = list
                                if (wants != null && wants.isNotEmpty() && techs.containsAll(wants)) match = true
                                list = null
                            }
                        }
                        event = xml.next()
                    }
                } finally {
                    xml.close()
                }
                match
            }
            .distinctBy { it.activityInfo.packageName }
            .map { Rival(it.activityInfo.packageName, it.activityInfo.applicationInfo.loadLabel(pm).toString()) }
    } catch (_: Exception) {
        emptyList()
    }

    /** The case this phone has talked to, or, before the first contact, anything with the case's kind of chip. */
    fun isCase(context: Context, tag: Tag): Boolean {
        val id = tag.id ?: return false
        val known = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UID, null)
        return if (known != null) hex(id) == known else id.isNotEmpty() && id[0] == FUDAN
    }

    /** Switches the sink on or off. Off, Android goes back to showing its own screen for the case. */
    fun setQuiet(context: Context, quiet: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, TagSinkActivity::class.java),
            if (quiet) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

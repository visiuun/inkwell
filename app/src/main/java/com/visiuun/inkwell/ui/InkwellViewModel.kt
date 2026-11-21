package com.visiuun.inkwell.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.visiuun.inkwell.BuildConfig
import com.visiuun.inkwell.data.DefaultCase
import com.visiuun.inkwell.data.Design
import com.visiuun.inkwell.data.Library
import com.visiuun.inkwell.data.Prefs
import com.visiuun.inkwell.data.Settings
import com.visiuun.inkwell.image.Composer
import com.visiuun.inkwell.image.Dither
import com.visiuun.inkwell.image.Ink
import com.visiuun.inkwell.image.InkImage
import com.visiuun.inkwell.image.Look
import com.visiuun.inkwell.image.Packer
import com.visiuun.inkwell.image.Placement
import com.visiuun.inkwell.image.TestCard
import com.visiuun.inkwell.nfc.CancelledException
import com.visiuun.inkwell.nfc.CaseException
import com.visiuun.inkwell.nfc.CaseInfo
import com.visiuun.inkwell.nfc.CaseLink
import com.visiuun.inkwell.nfc.CaseReader
import com.visiuun.inkwell.nfc.CaseSession
import com.visiuun.inkwell.nfc.CaseTags
import com.visiuun.inkwell.nfc.RefreshResult
import com.visiuun.inkwell.nfc.SimulatedCase
import com.visiuun.inkwell.nfc.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class Tab { CREATE, LIBRARY, SETTINGS }

/** Why the case hasn't been found yet. */
enum class WaitHint { SEARCHING, NUDGING, NEEDS_PERMISSION, RESEAT }

sealed interface Transfer {
    data object Idle : Transfer
    data class Waiting(val hint: WaitHint) : Transfer
    data object Connecting : Transfer
    data class Sending(val sent: Int, val total: Int) : Transfer
    data class Refreshing(val since: Long) : Transfer
    /**
     * [result] is null when the job only read the case details. [expected] is set for the test
     * card: what the panel should now look like.
     */
    data class Done(val result: RefreshResult?, val seconds: Int, val info: CaseInfo, val expected: ImageBitmap? = null) : Transfer
    data class Failed(val title: String, val message: String, val nfcOff: Boolean = false) : Transfer

    val active: Boolean get() = this is Waiting || this is Connecting || this is Sending || this is Refreshing
}

private sealed interface CaseJob {
    /** [image] builds the picture once the case has said how big its panel is; null means it can't. */
    class Push(val image: (CaseInfo) -> InkImage?, val keep: Boolean, val showExpected: Boolean = false) : CaseJob
    data object Read : CaseJob
}

data class Editor(
    val hasPhoto: Boolean = false,
    val loading: Boolean = false,
    val placement: Placement = Placement(),
    val look: Look = Look(),
    val preview: ImageBitmap? = null,
    val width: Int = DefaultCase.width,
    val height: Int = DefaultCase.height,
)

class DesignItem(val design: Design, val thumb: ImageBitmap)

sealed interface UiEvent {
    /** Ask the activity to blip the camera so NFC scans again. */
    data object Nudge : UiEvent
    data class Message(val text: String) : UiEvent
}

class InkwellViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val library = Library(app)
    private val nfc: NfcAdapter? = NfcAdapter.getDefaultAdapter(app)

    val settings: StateFlow<Settings> = prefs.settings
    val case: StateFlow<CaseInfo?> = prefs.case

    private val _tab = MutableStateFlow(Tab.CREATE)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _editor = MutableStateFlow(Editor(look = prefs.loadLook(), loading = library.draft.exists()))
    val editor: StateFlow<Editor> = _editor.asStateFlow()

    private val _designs = MutableStateFlow<List<DesignItem>>(emptyList())
    val designs: StateFlow<List<DesignItem>> = _designs.asStateFlow()

    private val _transfer = MutableStateFlow<Transfer>(Transfer.Idle)
    val transfer: StateFlow<Transfer> = _transfer.asStateFlow()

    private val _report = MutableStateFlow("")
    /** Everything worth knowing when a picture comes out wrong, as text to copy. */
    val report: StateFlow<String> = _report.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val nfcSupported: Boolean get() = nfc != null
    /** Without NFC hardware a debug build talks to a simulated case, so the flow can still be tried. */
    val demoMode: Boolean get() = nfc == null && BuildConfig.DEBUG

    // ---------------------------------------------------------------- editor

    private data class RenderInput(val stamp: Int, val placement: Placement, val look: Look, val width: Int, val height: Int)

    @Volatile private var source: Bitmap? = null
    @Volatile private var rendered: InkImage? = null
    private var sourceStamp = 0
    private val renderInput = MutableStateFlow(RenderInput(0, Placement(), _editor.value.look, DefaultCase.width, DefaultCase.height))

    init {
        viewModelScope.launch {
            renderInput.collectLatest { input ->
                val src = source
                fun show(image: InkImage) {
                    val preview = if (src == null) null else Composer.toBitmap(image, paper = true).asImageBitmap()
                    _editor.update { it.copy(preview = preview, width = input.width, height = input.height) }
                }
                // Smart takes a moment, too long to keep up with a finger. Its quick first version
                // goes up at once and the finished one follows as soon as the picture holds still.
                val twoSteps = src != null && input.look.dither == Dither.SMART
                val first = withContext(Dispatchers.Default) {
                    Composer.compose(src, input.width, input.height, input.placement, input.look, refine = !twoSteps)
                }
                // Until the finished version exists, sending renders its own.
                rendered = if (twoSteps) null else first
                show(first)
                if (twoSteps) {
                    val finished = withContext(Dispatchers.Default) {
                        val job = coroutineContext
                        Composer.compose(src, input.width, input.height, input.placement, input.look, keepGoing = { job.isActive })
                    }
                    rendered = finished
                    show(finished)
                }
            }
        }
        viewModelScope.launch {
            case.collect { info ->
                val target = info ?: DefaultCase
                renderInput.update { it.copy(width = target.width, height = target.height) }
            }
        }
        viewModelScope.launch {
            val draft = withContext(Dispatchers.IO) { if (library.draft.exists()) Composer.load(library.draft) else null }
            if (draft != null && source == null) setSource(draft)
            _editor.update { it.copy(loading = false) }
        }
        reloadDesigns()
        refreshReport()
        // Tags Android hands to the background sink reach a waiting job through here.
        CaseTags.listener = ::onTag
    }

    private fun setSource(bitmap: Bitmap?) {
        source = bitmap
        sourceStamp++
        _editor.update { it.copy(hasPhoto = bitmap != null, placement = Placement(), preview = if (bitmap == null) null else it.preview) }
        renderInput.update { it.copy(stamp = sourceStamp, placement = Placement()) }
    }

    fun pick(uri: Uri) {
        _tab.value = Tab.CREATE
        _editor.update { it.copy(loading = true) }
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                Composer.load(getApplication<Application>().contentResolver, uri)?.also { b ->
                    runCatching { library.draft.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                }
            }
            if (bitmap == null) _events.tryEmit(UiEvent.Message("Couldn't open that picture"))
            else setSource(bitmap)
            _editor.update { it.copy(loading = false) }
        }
    }

    fun removePhoto() {
        setSource(null)
        viewModelScope.launch(Dispatchers.IO) { library.draft.delete() }
    }

    fun setPlacement(transform: (Placement) -> Placement) {
        val next = transform(_editor.value.placement)
        _editor.update { it.copy(placement = next) }
        renderInput.update { it.copy(placement = next) }
    }

    /** Pinch and drag on the preview. [dx] and [dy] are fractions of the panel size. */
    fun gesture(dx: Float, dy: Float, zoom: Float) = setPlacement {
        it.copy(
            zoom = (it.zoom * zoom).coerceIn(0.2f, 8f),
            offsetX = (it.offsetX + dx).coerceIn(-2f, 2f),
            offsetY = (it.offsetY + dy).coerceIn(-2f, 2f),
        )
    }

    fun setLook(transform: (Look) -> Look) {
        val next = transform(_editor.value.look)
        _editor.update { it.copy(look = next) }
        renderInput.update { it.copy(look = next) }
        prefs.saveLook(next)
    }

    fun showTab(tab: Tab) {
        _tab.value = tab
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = prefs.settings.value
        prefs.update(transform)
        val after = prefs.settings.value
        if (before.quiet != after.quiet) runCatching { CaseTags.setQuiet(getApplication(), after.quiet) }
        refreshReport()
    }

    // ---------------------------------------------------------------- library

    private fun reloadDesigns() {
        viewModelScope.launch {
            val known = _designs.value.associateBy { it.design.id }
            val items = withContext(Dispatchers.IO) {
                library.list().mapNotNull { d ->
                    known[d.id]?.let { DesignItem(d, it.thumb) }
                        ?: library.load(d)?.let { DesignItem(d, Composer.toBitmap(it, paper = true).asImageBitmap()) }
                }
            }
            _designs.value = items
        }
    }

    fun delete(item: DesignItem) {
        _designs.update { list -> list.filterNot { it.design.id == item.design.id } }
        viewModelScope.launch(Dispatchers.IO) { library.delete(item.design) }
    }

    fun clearLibrary() {
        _designs.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { library.clear() }
    }

    // ---------------------------------------------------------------- talking to the case

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var job: CaseJob? = null
    private var lastJob: CaseJob? = null
    @Volatile private var cancel = false
    @Volatile private var tech: NfcA? = null
    @Volatile private var tagInUse: Tag? = null
    private var waitLoop: Job? = null

    /** The case's tag handle from its last announcement. Reused for the next job while it stays valid. */
    @Volatile private var held: Tag? = null

    private fun refreshReport() {
        val c = prefs.case.value
        val s = prefs.settings.value
        _report.value = buildString {
            append("Inkwell ${BuildConfig.VERSION_NAME}")
            if (c == null) {
                append("\nNo case read yet")
            } else {
                append("\nPanel ${c.width}x${c.height}, type ${"%02X".format(c.screenType)}, layout ${"%02X".format(c.mode)}, flag ${c.flag}")
                append("\nFirmware [${c.version}] build ${c.versionNumber ?: "?"}, maker ${c.manufacturer}, batch ${c.batch}, radio ${"%02X".format(c.radio)}")
                if (c.raw.isNotEmpty()) append("\nRaw ${c.raw}")
            }
            append("\nScan ${s.scan.name}, flip ${s.flip}, mirror ${s.mirror}")
            prefs.lastTransfer.takeIf { it.isNotEmpty() }?.let { append("\nLast: $it") }
        }
    }

    fun send() {
        val src = source ?: return
        val placement = _editor.value.placement
        val look = _editor.value.look
        start(CaseJob.Push(keep = true, image = { info ->
            rendered?.takeIf { it.width == info.width && it.height == info.height }
                ?: Composer.compose(src, info.width, info.height, placement, look)
        }))
    }

    fun send(item: DesignItem) = start(CaseJob.Push(keep = true, image = { info ->
        library.load(item.design)?.takeIf { it.width == info.width && it.height == info.height }
    }))

    fun wipe() = start(CaseJob.Push(keep = false, image = { info -> InkImage.solid(info.width, info.height, Ink.WHITE) }))

    /** Sends a known picture, so a wrong result on the panel can be read like a fingerprint. */
    fun sendTestCard() = start(CaseJob.Push(keep = false, showExpected = true, image = { info -> TestCard.render(info.width, info.height) }))

    fun readCase() = start(CaseJob.Read)

    fun retry() {
        lastJob?.let { start(it) }
    }

    private fun start(next: CaseJob) {
        if (_transfer.value.active) return
        cancel = false
        job = next
        lastJob = next
        when {
            demoMode -> {
                _transfer.value = Transfer.Connecting
                executor.execute { demo(next) }
            }
            nfc == null -> fail("No NFC here", "This phone has no NFC, so it can't reach the case.")
            !nfc.isEnabled -> fail("NFC is off", "Turn NFC on, then send again.", nfcOff = true)
            else -> {
                val tag = held
                if (tag != null && busy.compareAndSet(false, true)) {
                    // The case is still on the line from last time: no need to wait for it.
                    _transfer.value = Transfer.Connecting
                    executor.execute {
                        try {
                            talk(tag, next)
                        } finally {
                            busy.set(false)
                        }
                    }
                } else {
                    beginWaiting()
                }
            }
        }
    }

    private fun beginWaiting() {
        _transfer.value = Transfer.Waiting(WaitHint.SEARCHING)
        waitLoop?.cancel()
        waitLoop = viewModelScope.launch {
            // Give a case that is only now brought close a moment to show up on its own.
            delay(450)
            when {
                !settings.value.nudge -> delay(7000)
                CaseReader.canNudge(getApplication()) -> repeat(3) {
                    if (_transfer.value !is Transfer.Waiting) return@launch
                    _transfer.value = Transfer.Waiting(WaitHint.NUDGING)
                    _events.tryEmit(UiEvent.Nudge)
                    delay(3500)
                }
                else -> {
                    // Some phones re-announce an idle tag every few seconds by themselves; only
                    // bring up the camera question when that doesn't happen.
                    delay(4000)
                    if (_transfer.value is Transfer.Waiting) _transfer.value = Transfer.Waiting(WaitHint.NEEDS_PERMISSION)
                    return@launch
                }
            }
            if (_transfer.value is Transfer.Waiting) _transfer.value = Transfer.Waiting(WaitHint.RESEAT)
        }
    }

    /** The camera permission prompt was answered. */
    fun nudgeAnswered(granted: Boolean) {
        if (_transfer.value !is Transfer.Waiting) return
        if (granted) beginWaiting() else _transfer.value = Transfer.Waiting(WaitHint.RESEAT)
    }

    fun onTag(tag: Tag) {
        held = tag
        val current = job ?: return
        if (_transfer.value !is Transfer.Waiting) return
        if (!busy.compareAndSet(false, true)) return
        waitLoop?.cancel()
        _transfer.value = Transfer.Connecting
        executor.execute {
            try {
                talk(tag, current)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun talk(tag: Tag, current: CaseJob) {
        val nfcA = NfcA.get(tag)
        if (nfcA == null) {
            backToWaiting()
            return
        }
        var failure: CaseException? = null
        for (attempt in 1..3) {
            if (cancel) return finish(Transfer.Idle)
            try {
                try {
                    nfcA.connect()
                    nfcA.timeout = 3000
                } catch (e: Exception) {
                    // The handle went stale: the case has to be announced again.
                    if (held === tag) held = null
                    return backToWaiting()
                }
                tech = nfcA
                tagInUse = tag
                // The case's microcontroller boots from the reader's field.
                Thread.sleep(500)
                val link = CaseLink { frame ->
                    try {
                        nfcA.transceive(frame)
                    } catch (e: IOException) {
                        throw e
                    } catch (e: Exception) {
                        throw IOException(e.message ?: "NFC error", e)
                    }
                }
                return finish(converse(link, current))
            } catch (e: CancelledException) {
                return finish(Transfer.Idle)
            } catch (e: CaseException) {
                failure = e
                if (e.stage != Stage.LOGIN && e.stage != Stage.HANDSHAKE) break
            } catch (e: InterruptedException) {
                return finish(Transfer.Idle)
            } finally {
                tech = null
                runCatching { nfcA.close() }
            }
            Thread.sleep(350)
        }
        finish(describe(failure))
    }

    /** The whole exchange over an open link. Returns the state to end on. */
    private fun converse(link: CaseLink, current: CaseJob): Transfer {
        val began = System.currentTimeMillis()
        val session = CaseSession(link, cancelled = { cancel })
        session.login()
        var info = session.handshake()
        // This tag is the case: from now on only it is kept away from Android's tag screen.
        tagInUse?.let { CaseTags.remember(getApplication(), it) }
        when (current) {
            CaseJob.Read -> {
                val serial = try { session.readSerial() } catch (_: CaseException) { null }
                info = info.copy(serial = serial)
                prefs.saveCase(info)
                refreshReport()
                return Transfer.Done(null, 0, info)
            }
            is CaseJob.Push -> {
                prefs.saveCase(info)
                val image = current.image(info)
                    ?: throw CaseException(Stage.PREPARE, "This design was made for a different panel than this ${info.width} × ${info.height} one.")
                var out = image
                val s = settings.value
                if (s.flip) out = out.rotated180()
                if (s.mirror) out = out.mirrored()
                val result = try {
                    session.sendImage(
                        Packer.pack(out, s.scan),
                        onProgress = { sent, total -> _transfer.value = Transfer.Sending(sent, total) },
                        onRefreshing = { _transfer.value = Transfer.Refreshing(System.currentTimeMillis()) },
                    )
                } catch (e: CaseException) {
                    prefs.lastTransfer = "${session.summary} -> failed at ${e.stage}"
                    refreshReport()
                    throw e
                }
                prefs.lastTransfer = "${session.summary} -> $result"
                refreshReport()
                if (cancel) return Transfer.Idle
                if (result != RefreshResult.FAILED && current.keep && s.keepHistory) {
                    library.save(image)
                    reloadDesigns()
                }
                val expected = if (current.showExpected) Composer.toBitmap(image, paper = true).asImageBitmap() else null
                return Transfer.Done(result, ((System.currentTimeMillis() - began) / 1000).toInt(), info, expected)
            }
        }
    }

    private fun demo(current: CaseJob) {
        val case = SimulatedCase(refreshPolls = 45)
        val link = CaseLink { frame ->
            Thread.sleep(if (frame.size > 100) 18 else 6)
            case.transceive(frame)
        }
        val end = try {
            Thread.sleep(600)
            converse(link, current)
        } catch (e: CancelledException) {
            Transfer.Idle
        } catch (e: CaseException) {
            describe(e)
        }
        finish(end)
    }

    private fun describe(e: CaseException?): Transfer.Failed = when (e?.stage) {
        Stage.LOGIN -> Transfer.Failed("Couldn't unlock the case", "${e.message}. Check that the phone sits properly in the case, then try again.")
        Stage.HANDSHAKE -> Transfer.Failed("The case didn't answer", "${e.message}. Keep the phone still against the case and try again.")
        Stage.PREPARE -> Transfer.Failed("The case said no", e.message ?: "The case didn't accept the picture.")
        Stage.TRANSFER -> Transfer.Failed("Transfer interrupted", "The picture didn't make it across. Keep the phone still and try again.")
        else -> Transfer.Failed("Something went wrong", e?.message ?: "Try again.")
    }

    private fun backToWaiting() {
        if (cancel) return finish(Transfer.Idle)
        viewModelScope.launch { if (job != null) beginWaiting() }
    }

    private fun finish(end: Transfer) {
        job = null
        _transfer.value = end
    }

    private fun fail(title: String, message: String, nfcOff: Boolean = false) = finish(Transfer.Failed(title, message, nfcOff))

    /** Stops whatever is going on and closes the sheet. */
    fun dismiss() {
        val state = _transfer.value
        if (!state.active) {
            _transfer.value = Transfer.Idle
            return
        }
        cancel = true
        waitLoop?.cancel()
        if (state is Transfer.Waiting) {
            finish(Transfer.Idle)
        } else {
            // Closing the link from here makes the blocked exchange on the worker thread give up.
            val open = tech
            Thread { runCatching { open?.close() } }.start()
        }
    }

    override fun onCleared() {
        CaseTags.listener = null
        cancel = true
        executor.shutdownNow()
    }
}

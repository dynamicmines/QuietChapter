package com.quietchapter

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** Parked debug client. Only the service owns playback and navigation scheduling. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var connection: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null
    private var snapshot = Bundle()
    private var active = false
    private var preparing = false
    private var dragging = false
    private lateinit var ui: ParkedPlayerView
    private var parkedAllowed = false
    private var parkedMessage = "Checking parked UI availability"
    private var parkedGate: AutoCloseable? = null
    private var uiError: String? = null
    private var connectionError = false
    private var importResult: android.content.Intent? = null
    private lateinit var status: TextView
    private lateinit var route: TextView
    private lateinit var log: TextView
    private lateinit var seek: SeekBar
    private lateinit var play: Button
    private lateinit var prepare: Button
    private val ticker = object : Runnable {
        override fun run() {
            browser?.let { snapshot = it.sessionExtras }
            render()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val day = getSharedPreferences("presentation", MODE_PRIVATE).getBoolean("day", false)
        setTheme(if (day) R.style.AppThemeDay else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        ui = ParkedPlayerView(this)
        ui.restoreState(savedInstanceState)
        setContentView(ui.root)
        status = ui.status; route = ui.route; log = ui.log
        seek = ui.seek; play = ui.play; prepare = ui.prepare
        ui.root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        prepare.setOnClickListener {
            if (parkedAllowed) {
                uiError = null
                preparing = true
                command(QuietCommands.PREPARE) { preparing = false }
                render()
            }
        }
        ui.importAudio.setOnClickListener {
            if (parkedAllowed && browser != null) {
                browser?.pause()
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "audio/*"
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }, 601)
                } catch (failure: android.content.ActivityNotFoundException) {
                    uiError = getString(R.string.import_unavailable)
                    render()
                }
            }
        }
        ui.recoveryMode.setOnClickListener {
            if (parkedAllowed) {
                val modes = com.quietchapter.recovery.RecoveryMode.entries
                val current = modes.indexOfFirst { it.name == snapshot.getString("recoveryMode") }.coerceAtLeast(0)
                command(QuietCommands.RECOVERY_MODE, Bundle().apply { putString("mode", modes[(current + 1) % modes.size].name) })
            }
        }
        ui.appearance.setOnClickListener {
            if (parkedAllowed) {
                getSharedPreferences("presentation", MODE_PRIVATE).edit().putBoolean("day", !day).apply()
                recreate()
            }
        }
        ui.localAudio.setOnClickListener { selectChapter(com.quietchapter.media.LocalAudioStore.LOCAL_ID) }
        play.setOnClickListener { if (parkedAllowed) {
            if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                android.util.Log.d("QuietInput", "${android.os.SystemClock.elapsedRealtime()} UI Play click")
            browser?.play()
        } }
        ui.prepareShortcut.setOnClickListener { if (parkedAllowed && prepare.isEnabled) prepare.performClick() }
        ui.continueListening.setOnClickListener { if (parkedAllowed) {
            ui.showPage("player")
            if (browser?.isPlaying == true) browser?.pause()
            else if (play.isEnabled) browser?.play() else if (prepare.isEnabled) prepare.performClick()
        } }
        ui.previous.setOnClickListener { if (parkedAllowed) browser?.seekToPreviousMediaItem() }
        ui.next.setOnClickListener { if (parkedAllowed) browser?.seekToNextMediaItem() }
        ui.pause.setOnClickListener { if (parkedAllowed) browser?.pause() }
        ui.rewind.setOnClickListener { if (parkedAllowed) browser?.seekBack() }
        ui.simulate.setOnClickListener { if (parkedAllowed) command(QuietCommands.SIMULATE) }
        ui.stopSimulation.setOnClickListener { if (parkedAllowed) command(QuietCommands.STOP_SIMULATION) }
        ui.chapterOne.setOnClickListener { selectChapter(com.quietchapter.media.DemoCatalog.CHAPTER_ONE_ID) }
        ui.chapterTwo.setOnClickListener { selectChapter(com.quietchapter.media.DemoCatalog.CHAPTER_TWO_ID) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !dragging && parkedAllowed) browser?.let {
                    val duration = snapshot.getLong("durationMs")
                    if (duration > 0) it.seekTo(duration * progress / seek.max)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                if (parkedAllowed) browser?.let {
                    val duration = snapshot.getLong("durationMs")
                    if (duration > 0) it.seekTo(duration * seek.progress / seek.max)
                }
                dragging = false
            }
        })
    }

    @Deprecated("Platform document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 601 && resultCode == RESULT_OK && data?.data != null) {
            importResult = data
            consumeImportResult()
        } else if (requestCode == 601) {
            uiError = getString(R.string.import_not_selected)
            render()
        }
    }

    private fun consumeImportResult() {
        val selected = importResult ?: return
        if (!active || browser == null) return
        // A picker may outlive a vehicle-state transition. Wait for the current callback,
        // then refuse a result if this parked UI is no longer permitted.
        if (!parkedAllowed) return
        importResult = null
        uiError = null
        command(QuietCommands.IMPORT_AUDIO, args = Bundle().apply {
            putString("uri", selected.data.toString())
            putInt("flags", selected.flags)
        })
    }

    private fun selectChapter(id: String) {
        if (!parkedAllowed) return
        uiError = null
        browser?.let {
            // Selection is deliberately paused. A separate explicit Play starts the chapter.
            it.pause()
            it.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId(id).build())
            it.prepare()
            ui.showPage("player")
        }
    }

    override fun onStart() {
        super.onStart()
        active = true
        preparing = false
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            parkedAllowed = false
            if (android.os.Build.VERSION.SDK_INT >= 29) parkedGate = AutomotiveParkedGate(this) { allowed, message ->
                parkedAllowed = allowed
                parkedMessage = message
                consumeImportResult()
                render()
            }
            else parkedMessage = "Vehicle UI status unavailable; use system media controls"
        } else {
            parkedAllowed = true
            parkedMessage = "Parked demonstration only · no vehicle state on this device"
        }
        val pending = MediaBrowser.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        connection = pending
        pending.addListener({
            if (active && connection === pending) {
                try {
                    browser = pending.get()
                    if (connectionError) uiError = null
                    connectionError = false
                    snapshot = browser!!.sessionExtras
                    command(QuietCommands.SNAPSHOT)
                    consumeImportResult()
                } catch (failure: Exception) {
                    connectionError = true
                    uiError = getString(R.string.media_connection_failed)
                }
                render()
            }
        }, mainExecutorCompat())
        handler.post(ticker)
    }

    private fun command(action: String, args: Bundle = Bundle.EMPTY, done: (() -> Unit)? = null) {
        val client = browser ?: return
        val pending = client.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        pending.addListener({
            if (active) {
                try {
                    val result = pending.get()
                    if (result.resultCode == SessionResult.RESULT_SUCCESS) snapshot = result.extras
                    else { snapshot = result.extras; uiError = snapshot.getString("mediaError")?.takeIf { it.isNotEmpty() } ?: getString(R.string.audio_preparation_failed) }
                } catch (failure: Exception) { uiError = getString(R.string.audio_preparation_failed) }
                done?.invoke()
                render()
            }
        }, mainExecutorCompat())
    }

    private fun render() {
        if (!::status.isInitialized) return
        val duration = snapshot.getLong("durationMs")
        val position = snapshot.getLong("positionMs")
        val state = snapshot.getString("state") ?: "CONNECTING"
        status.text = "$state · playing=${snapshot.getBoolean("actualPlaying")}\n${position / 1000} / ${duration / 1000} seconds"
        route.text = snapshot.getString("route") ?: "SIMULATED source idle — no route"
        log.text = snapshot.getString("log") ?: "Connecting"
        val local = snapshot.getBoolean("localSelected")
        val ready = snapshot.getBoolean("demoReady") || (local && snapshot.getBoolean("localAvailable"))
        ui.contextMode.text = snapshot.getString("contextMode") ?: getString(R.string.basic_playback)
        ui.recoveryMode.setText(when (snapshot.getString("recoveryMode")) {
            "FIXED_BACKTRACK" -> R.string.resume_fixed
            "NARRATIVE" -> R.string.resume_narrative
            "ACOUSTIC" -> R.string.resume_acoustic
            else -> R.string.resume_exact
        })
        ui.recoveryDetail.setText(when (snapshot.getString("recoveryMode")) {
            "NARRATIVE" -> if (snapshot.getBoolean("narrativeReady")) R.string.narrative_recovery_rule else R.string.narrative_unavailable
            "ACOUSTIC" -> if (snapshot.getBoolean("acousticIndexVerified") && !snapshot.getBoolean("localSelected"))
                R.string.resume_acoustic_ready else R.string.resume_acoustic_fallback
            "FIXED_BACKTRACK" -> R.string.resume_fixed_detail
            else -> R.string.resume_exact_detail
        })
        ui.parkedControls.forEach { it.isEnabled = parkedAllowed && browser != null }
        play.isEnabled = parkedAllowed && browser != null && ready && !snapshot.getBoolean("importing")
        prepare.isEnabled = parkedAllowed && browser != null && !preparing
        prepare.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
        ui.prepareShortcut.visibility = prepare.visibility
        ui.prepareShortcut.isEnabled = prepare.isEnabled
        ui.continueListening.isEnabled = parkedAllowed && browser != null && !preparing && !snapshot.getBoolean("importing")
        ui.continueListening.setText(if (ready) {
            if (snapshot.getBoolean("actualPlaying")) R.string.pause else R.string.continue_listening
        } else R.string.prepare)
        prepare.setText(if (preparing) R.string.preparing_audio else R.string.prepare)
        seek.isEnabled = parkedAllowed && browser != null && duration > 0
        ui.chapterOne.isEnabled = parkedAllowed && browser != null && snapshot.getBoolean("demoReady")
        ui.chapterTwo.isEnabled = ui.chapterOne.isEnabled
        ui.localAudio.visibility = if (snapshot.getBoolean("localAvailable")) android.view.View.VISIBLE else android.view.View.GONE
        ui.localAudio.text = snapshot.getString("localTitle") ?: getString(R.string.imported_audio)
        ui.title.text = if (local) snapshot.getString("selectedTitle") else getString(R.string.book_title)
        ui.parkedNotice.text = if (parkedAllowed) getString(R.string.parked_notice) else parkedMessage
        ui.author.text = if (local) getString(R.string.local_single_chapter) else getString(R.string.book_author)
        ui.artwork.setImageResource(if (local) R.drawable.ic_quietchapter else R.drawable.cover_story)
        ui.artwork.contentDescription = if (local) getString(R.string.imported_audio) else getString(R.string.artwork_description)
        val id = snapshot.getString("mediaId")
        val second = id == com.quietchapter.media.DemoCatalog.CHAPTER_TWO_ID ||
            (id == com.quietchapter.media.DemoCatalog.BOOK_ID && position >= 24_000)
        ui.chapter.setText(if (local) R.string.local_single_chapter else if (second) R.string.now_chapter_two else R.string.now_chapter_one)
        ui.chapterOne.isSelected = !local && !second
        ui.chapterTwo.isSelected = !local && second
        ui.elapsed.text = clockText(position)
        ui.remaining.text = if (duration > 0) getString(R.string.remaining_time,
            clockText((duration - position).coerceAtLeast(0))) else getString(R.string.remaining_unknown)
        ui.playback.setText(when (state) {
            "PAUSE_PENDING" -> R.string.state_narrative_pending
            "AUTO_PAUSED" -> R.string.state_auto_paused
            "USER_PAUSED" -> R.string.state_user_paused
            "INTERRUPTED" -> R.string.state_interrupted
            "ENDED" -> R.string.state_ended
            "IDLE" -> if (ready && position > 0) R.string.state_restored_paused else R.string.state_ready
            "NORMAL" -> if (snapshot.getBoolean("actualPlaying")) R.string.state_playing else R.string.state_buffering
            else -> R.string.state_ready
        })
        ui.recovery.setText(when (state) {
            "PAUSE_PENDING" -> R.string.narrative_waiting
            "AUTO_PAUSED" -> R.string.recovery_auto
            "USER_PAUSED" -> R.string.recovery_manual
            "INTERRUPTED" -> R.string.recovery_interrupted
            "ENDED" -> R.string.recovery_ended
            "NORMAL" -> if (snapshot.getBoolean("actualPlaying")) R.string.recovery_playing else R.string.recovery_buffering
            "IDLE" -> if (ready && position > 0) R.string.recovery_saved else R.string.recovery_idle
            else -> R.string.recovery_idle
        })
        ui.health.setText(when (snapshot.getString("navigationHealth")) {
            "AVAILABLE" -> R.string.health_available
            "EXPIRED", "STALE" -> R.string.health_expired
            "UNRELIABLE" -> R.string.health_unreliable
            "DISCONNECTED" -> R.string.health_disconnected
            "REROUTING" -> R.string.health_rerouting
            else -> R.string.source_idle
        })
        if (local && !snapshot.getBoolean("localPersistent")) ui.recovery.setText(R.string.import_temporary)
        snapshot.getString("mediaError")?.takeIf { it.isNotEmpty() }?.let { ui.recovery.text = it }
        uiError?.let { ui.recovery.text = it }
        ui.present(parkedAllowed, parkedMessage, snapshot.getBoolean("narrativeReady"), local, snapshot.getBoolean("actualPlaying"))
        if (!dragging) seek.progress = if (duration > 0) (position * 1000 / duration).toInt() else 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        ui.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        active = false
        parkedAllowed = false
        parkedGate?.close()
        parkedGate = null
        handler.removeCallbacksAndMessages(null)
        connection?.let { MediaController.releaseFuture(it) }
        connection = null
        browser = null
        super.onStop()
    }

    private fun clockText(ms: Long): String = java.lang.String.format(java.util.Locale.ROOT,
        "%d:%02d", ms.coerceAtLeast(0) / 60_000, (ms.coerceAtLeast(0) / 1_000) % 60)

    private fun mainExecutorCompat() = java.util.concurrent.Executor { action -> handler.post(action) }
}

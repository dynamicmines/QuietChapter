package com.quietchapter

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.quietchapter.core.NavigationEvent
import com.quietchapter.core.NavigationSignal
import com.quietchapter.core.PlaybackAction
import com.quietchapter.core.PlaybackCoordinator
import com.quietchapter.core.SimulatedNavigationSource
import com.quietchapter.media.LocalAudio
import com.quietchapter.media.LocalAudioStore
import com.quietchapter.media.DemoCatalog
import com.quietchapter.media.PlaybackBookmark
import com.quietchapter.media.PlaybackBookmarkStore
import com.quietchapter.recovery.*
import com.quietchapter.context.*
import com.quietchapter.narrative.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackService : MediaLibraryService() {
    private val main = Handler(Looper.getMainLooper())
    private val simulation = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val coordinator = PlaybackCoordinator()
    private var narrativeTimeline: NarrativeTimeline? = null
    private val narrativeOrchestrator = AdaptiveNarrativeOrchestrator()
    private var pendingNarrativePause: SoftPausePlan? = null
    private var narrativeListenFloorMs: Long? = null
    private var manualNarrativeSeek = false
    private var narrativeCacheHit = false
    private var narrativeStatus = "Narrative metadata unavailable"
    private var lastNarrativeAnchor = ""
    private var recoveryAtCompletedSentence = false
    private var recoverySentenceEndTarget: Long? = null
    private var narrativeRecoveryPrepared = false
    private var lastRecoveryTargetMs = -1L
    private val narrativeTimer = object : Runnable {
        override fun run() {
            val plan = pendingNarrativePause ?: return
            val selection = narrativeSelection()
            val eligible = !destroyed && recoveryMode == RecoveryMode.NARRATIVE && narrativeReady() &&
                coordinator.state == "PAUSE_PENDING" && coordinator.navigationHealth == "AVAILABLE" && audio.player.isPlaying && selection?.chapterId == plan.chapterId
            when (narrativeOrchestrator.poll(plan, selection?.absolutePositionMs ?: -1,
                SystemClock.elapsedRealtime(), lastAcceptedNavigationGeneration, mediaRevision, eligible)) {
                SoftPauseDecision.WAIT -> main.postDelayed(this, 20)
                SoftPauseDecision.PAUSE_NOW -> {
                    pendingNarrativePause = null
                    lastNarrativeAnchor = plan.annotationId
                    apply(coordinator.commitScheduledPause(), "SIMULATED narrative boundary ${plan.annotationId}",
                        completedSentenceTargetMs = plan.targetMs)
                }
                SoftPauseDecision.CANCEL -> cancelNarrativePause()
            }
        }
    }
    private val recoveryPlanner = RecoveryPlanner()
    private val recoveryTicket = RecoveryTicket()
    private var recoveryMode = RecoveryMode.EXACT
    private var lastRecovery = "Exact resume · no pending recovery"
    private var lastPlatformContext = "No platform interruption observed"
    private var recoverySeeks = 0
    private var acousticBoundaries = emptyList<Long>()
    private var indexedSourceHash = ""
    private var originalIndexVerified = false
    private val debugBuild get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val progressGuard = com.quietchapter.media.PlaybackProgressGuard()
    private val instance = UUID.randomUUID().toString()
    private lateinit var audio: AudioPlaybackController
    private var facade: PolicySessionPlayer? = null
    private var session: MediaLibrarySession? = null
    private lateinit var bookmarks: PlaybackBookmarkStore
    private lateinit var localStore: LocalAudioStore
    private var localAudio: LocalAudio? = null
    private var mediaError = ""
    private var importing = false
    private var loadingLocal = false
    private var unavailableSelection = false
    private lateinit var demoFile: File
    private var speech: DemoSpeech? = null
    private var preparation: SettableFuture<File>? = null
    private var intentGeneration = 0
    private var selectionGeneration = 0
    private var registrationGeneration = 0
    private var awaitingPlay = false
    private var nextPreparationDelayMs = 0L
    private var resolutionGeneration = 0
    private var nextResolutionDelayMs = 0L
    private val pendingResolutions = mutableSetOf<SettableFuture<List<MediaItem>>>()
    private var cancellingResolutions = false
    private var mediaRevision = 0
    private var destroyed = false
    private var lastSaveMs = 0L
    private var debugFrozenUntilMs = 0L
    private var debugFrozenPositionMs = 0L
    private var restoringPosition: Long? = null
    private var actualStarts = 0
    private var lastAcceptedNavigationGeneration = -1L
    private var simulationGeneration = 0L
    private var routeLabel = "SIMULATED source idle — no route"
    private val entries = ArrayDeque<String>()
    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            val mode = (getSystemService(AUDIO_SERVICE) as AudioManager).mode
            if (mode != AudioManager.MODE_NORMAL && (awaitingPlay || pendingResolutions.isNotEmpty() || coordinator.state in listOf("NORMAL", "PAUSE_PENDING", "AUTO_PAUSED"))) {
                interrupt("System call/communication audio mode")
            }
            val action = coordinator.onTick(SystemClock.elapsedRealtime())
            if (action != PlaybackAction.NONE) {
                routeLabel = "SIMULATED source unavailable — events expired"
                apply(action, "Navigation expired")
            }
            if (coordinator.state != "PAUSE_PENDING" && pendingNarrativePause != null) cancelNarrativePause()
            val advancingExpected = coordinator.state in listOf("NORMAL", "PAUSE_PENDING") && audio.player.playWhenReady &&
                audio.player.playbackState in listOf(Player.STATE_BUFFERING, Player.STATE_READY)
            val now = SystemClock.elapsedRealtime()
            val observedPosition = if (now < debugFrozenUntilMs) debugFrozenPositionMs else audio.player.currentPosition
            if (progressGuard.stalled(advancingExpected, observedPosition, now)) {
                mediaError = "Audio progress stalled; playback paused. Check the output, then explicitly retry Play."
                interrupt("Playback clock stalled")
            }
            settleRecoveryPosition()
            if (SystemClock.elapsedRealtime() - lastSaveMs >= 1_000) saveBookmark()
            publish()
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate() {
        super.onCreate()
        recoveryMode = runCatching { RecoveryMode.valueOf(getSharedPreferences("recovery", MODE_PRIVATE)
            .getString("mode", RecoveryMode.EXACT.name)!!) }.getOrDefault(RecoveryMode.EXACT)
        runCatching {
            val index = org.json.JSONObject(assets.open("demo-acoustic-index.json").bufferedReader().use { it.readText() })
            indexedSourceHash = index.getString("sourceSha256")
            val values = index.getJSONArray("boundariesMs")
            acousticBoundaries = (0 until values.length()).map { values.getLong(it) }
        }
        DemoSpeech.refreshKnownBundled(this)
        DemoCatalog.setArtwork(resources.openRawResource(R.raw.cover_thumb).use { it.readBytes() })
        bookmarks = PlaybackBookmarkStore(this)
        localStore = LocalAudioStore(this)
        demoFile = File(noBackupFilesDir, "quietchapter-original-v1.wav")
        verifyOriginalIndex()
        loadNarrativeTimeline()
        audio = AudioPlaybackController(this,
            interrupted = { interrupt(it) },
            finished = { apply(coordinator.ended(), "Media ended") },
            changed = {
                if (::audio.isInitialized) {
                    val duration = audio.player.duration
                    restoringPosition?.let { target ->
                        if (duration != C.TIME_UNSET && duration >= 0 && audio.player.playbackState == Player.STATE_READY) {
                            restoringPosition = null
                            if (target > duration) audio.player.seekTo(duration)
                        }
                    }
                    publish()
                }
            })
        audio.player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    progressGuard.reset()
                    if (manualNarrativeSeek) { narrativeListenFloorMs = newPosition.positionMs; manualNarrativeSeek = false }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                mediaError = "Audio unavailable or unsupported. Re-import the file or select the original demo."
                publish()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    actualStarts++
                    narrativeRecoveryPrepared = false
                    if (narrativeListenFloorMs == null) narrativeListenFloorMs = audio.player.currentPosition
                    if (recoveryMode != RecoveryMode.NARRATIVE) recoverFromTicket()
                }
                Log.i("QuietChapter", "Actual player playing=$isPlaying starts=$actualStarts service=$instance")
                publish()
            }
        })
        facade = PolicySessionPlayer(audio.player,
            userPlayback = { playing ->
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    val caller = session?.controllerForCurrentRequest
                    Log.d("QuietInput", "${SystemClock.elapsedRealtime()} play=$playing package=${caller?.packageName ?: "direct"} uid=${caller?.uid ?: -1}")
                }
                if (playing) requestPlay() else pause() },
            userStop = { newSelection(); interrupt("Standard Stop"); stopSimulation(false) },
            changingMedia = { newSelection(); mediaRevision++; pause() },
            chapterNavigation = { forward -> skipChapter(forward) },
            manualSeek = {
                cancelNarrativePause(); clearRecovery(); narrativeRecoveryPrepared = false; manualNarrativeSeek = true; narrativeListenFloorMs = null
                val revision = mediaRevision
                main.post { if (manualNarrativeSeek && revision == mediaRevision) {
                    narrativeListenFloorMs = audio.player.currentPosition; manualNarrativeSeek = false
                } }
                lastRecovery = "Manual seek · recovery cancelled"
            })
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, facade!!, LibraryCallbacks())
            .setSessionActivity(pending).build()
        val saved = bookmarks.read()
        if (saved?.mediaId != LocalAudioStore.LOCAL_ID) DemoCatalog.resolve(saved?.mediaId ?: DemoCatalog.BOOK_ID, demoFile)?.let { item ->
            restoringPosition = saved?.positionMs
            audio.player.setMediaItem(item)
            audio.player.seekTo(saved?.positionMs ?: 0)
            audio.player.prepare()
        }
        loadingLocal = saved?.mediaId == LocalAudioStore.LOCAL_ID
        unavailableSelection = loadingLocal
        val restoreGeneration = selectionGeneration
        val restoreRegistration = registrationGeneration
        io.execute {
            val stored = localStore.read()
            val valid = stored?.let { runCatching { localStore.validate(it) }.getOrNull() }
            val delay = debugRestoreDelayMs()
            main.postDelayed({
                loadingLocal = false
                if (!destroyed) {
                    if (restoreRegistration == registrationGeneration) localAudio = valid
                    if (restoreGeneration == selectionGeneration && saved?.mediaId == LocalAudioStore.LOCAL_ID) {
                        if (valid == null) mediaError = "Saved audio is inaccessible. Re-import it or prepare the original demo."
                        else {
                            unavailableSelection = false
                            val position = saved.positionFor(LocalAudioStore.LOCAL_ID, valid.contentIdentity)
                            restoringPosition = position
                            audio.player.setMediaItem(valid.toMediaItem())
                            audio.player.seekTo(position)
                            audio.player.prepare()
                        }
                    }
                    publish()
                }
            }, delay)
        }
        record("Service created $instance; restored position only, explicit Play required")
        main.post(ticker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    private fun requestPlay() {
        if (destroyed || cancellingResolutions) {
            record("Stale queued Play ignored while cancelling/closing")
            return
        }
        if (importing || loadingLocal) { record("Play ignored during document validation; explicit Play required after import"); return }
        if (unavailableSelection && audio.player.mediaItemCount == 0) {
            mediaError = "Selected audio is unavailable. Re-import it or prepare the original demo."
            record("Play rejected: selected document unavailable; explicit import/demo selection required")
            publish()
            return
        }
        mediaError = ""
        val generation = ++intentGeneration
        if (audio.player.mediaItemCount > 0 && nextPreparationDelayMs == 0L) {
            awaitingPlay = false
            apply(coordinator.userPlay(), "Standard Play")
            return
        }
        awaitingPlay = true
        val delayMs = nextPreparationDelayMs
        nextPreparationDelayMs = 0
        val ready = ensureDemo()
        ready.addListener({
            val completion = Runnable {
              if (!destroyed && generation == intentGeneration) {
                awaitingPlay = false
                try {
                    val file = ready.get()
                    if (audio.player.mediaItemCount == 0) {
                        val item = DemoCatalog.resolve(DemoCatalog.BOOK_ID, file) ?: error("Missing demo")
                        audio.player.setMediaItem(item)
                        audio.player.prepare()
                    }
                    apply(coordinator.userPlay(), "Standard Play after preparation")
                } catch (failure: Exception) { interrupt("Demo preparation failed") }
              }
            }
            if (delayMs > 0) main.postDelayed(completion, delayMs) else completion.run()
        }, mainExecutorCompat())
    }

    private fun newSelection() {
        narrativeRecoveryPrepared = false
        cancelNarrativePause()
        narrativeListenFloorMs = null
        manualNarrativeSeek = false
        clearRecovery()
        progressGuard.reset()
        selectionGeneration++
        restoringPosition = null
    }

    private fun debugRestoreDelayMs(): Long {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return 0
        val gate = File(noBackupFilesDir, "test-restore-delay-ms")
        return try { gate.takeIf { it.length() in 1..16 }?.readText()?.toLongOrNull()?.coerceIn(0, 10_000) ?: 0 }
        finally { gate.delete() }
    }

    private fun pause() {
        narrativeRecoveryPrepared = false
        cancelNarrativePause()
        clearRecovery()
        progressGuard.reset()
        intentGeneration++
        awaitingPlay = false
        cancelResolutions()
        apply(coordinator.userPause(), "Standard Pause")
        saveBookmark()
    }

    private fun interrupt(reason: String) {
        val wasNarrativePlaying = coordinator.state == "PAUSE_PENDING"
        cancelNarrativePause()
        // Media3 remains the focus/noisy owner. Capture only the interrupted active chapter.
        val platformInterruption = reason.startsWith("Audio focus") || reason == "Audio output became noisy"
        if (platformInterruption) lastPlatformContext = "ANDROID_PLATFORM · LIVE · ${SystemClock.elapsedRealtime()} ms · $reason"
        if ((coordinator.state == "NORMAL" || wasNarrativePlaying) && platformInterruption && !narrativeRecoveryPrepared) rememberRecovery()
        else if (!(platformInterruption && coordinator.state == "INTERRUPTED")) clearRecovery()
        progressGuard.reset()
        intentGeneration++
        awaitingPlay = false
        cancelResolutions()
        apply(coordinator.interrupt(reason), reason)
    }

    private fun apply(action: PlaybackAction, reason: String, completedSentenceTargetMs: Long? = null) {
        val navigationPause = action == PlaybackAction.PAUSE && reason.startsWith("SIMULATED") &&
            coordinator.state == "AUTO_PAUSED" && audio.player.playWhenReady
        if (action == PlaybackAction.PAUSE && reason == "Navigation expired") clearRecovery()
        if (action == PlaybackAction.PLAY) settleRecoveryPosition()
        val playGeneration = intentGeneration
        if (action == PlaybackAction.PLAY && recoveryMode == RecoveryMode.NARRATIVE &&
            (getSystemService(AUDIO_SERVICE) as AudioManager).mode == AudioManager.MODE_NORMAL &&
            audio.player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            // Seek while paused, before Media3's own focus request/first emitted resumed sample.
            // A denied fresh focus request can leave this once-prepared anchor paused.
            recoverFromTicket()
        }
        if (action == PlaybackAction.PLAY && (destroyed || playGeneration != intentGeneration || coordinator.state != "NORMAL")) {
            record("Play revoked during recovery preparation")
            return
        }
        audio.apply(action)
        // Capture the stopped clock, not its still-advancing estimate just before Pause.
        if (navigationPause && coordinator.state == "AUTO_PAUSED") {
            rememberRecovery(completedSentenceTargetMs)
        }
        record("$reason → ${coordinator.state} / $action")
        facade?.refresh()
        publish()
    }

    private fun clearRecovery() { recoveryTicket.clear(); recoveryAtCompletedSentence = false; recoverySentenceEndTarget = null }

    private fun rememberRecovery(completedSentenceTargetMs: Long? = null) {
        recoverySentenceEndTarget = completedSentenceTargetMs
        recoveryTicket.remember(audio.player.currentMediaItem?.mediaId.orEmpty(), mediaRevision, audio.player.currentPosition)
        settleRecoveryPosition()
    }

    private fun settleRecoveryPosition() {
        if (!::audio.isInitialized || !recoveryTicket.pending || audio.player.playWhenReady || audio.player.isPlaying) return
        recoveryTicket.settle(audio.player.currentMediaItem?.mediaId.orEmpty(), mediaRevision, audio.player.currentPosition)
        val end = recoverySentenceEndTarget
        val stopped = narrativeSelection()?.absolutePositionMs
        recoveryAtCompletedSentence = end != null && stopped != null && stopped in end..end + 80
    }

    private fun verifyOriginalIndex() {
        originalIndexVerified = false
        io.execute {
            val hash = runCatching {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                demoFile.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
            main.post { if (!destroyed) originalIndexVerified = hash == indexedSourceHash && hash != null }
        }
    }

    private data class NarrativeSelection(val chapterId: String, val absolutePositionMs: Long, val offsetMs: Long)

    private fun narrativeSelection(position: Long = audio.player.currentPosition): NarrativeSelection? {
        if (position < 0) return null
        return when (audio.player.currentMediaItem?.mediaId) {
            DemoCatalog.BOOK_ID -> NarrativeSelection(if (position < 24_000) "chapter:1" else "chapter:2", position, 0)
            DemoCatalog.CHAPTER_ONE_ID -> NarrativeSelection("chapter:1", position, 0)
            DemoCatalog.CHAPTER_TWO_ID -> NarrativeSelection("chapter:2", position + 24_000, 24_000)
            else -> null
        }
    }

    private fun narrativeReady(): Boolean = ::audio.isInitialized && originalIndexVerified &&
        narrativeTimeline?.let { it.valid && it.hasVerifiedSentences && it.assetSha256 == indexedSourceHash } == true &&
        narrativeSelection() != null

    private fun loadNarrativeTimeline() {
        io.execute {
            var cacheHit = false
            val loaded = runCatching {
                require(indexedSourceHash.matches(Regex("[0-9a-f]{64}")))
                val preparedText = assets.open("demo-narrative-map.json").bufferedReader().use { it.readText() }
                val prepared = NarrativeTimelineCodec.decode(preparedText) ?: error("Invalid prepared narrative map")
                require(prepared.assetSha256 == indexedSourceHash)
                val analysisKey = org.json.JSONObject(preparedText).getString("cacheKey")
                require(analysisKey.matches(Regex("[0-9a-f]{64}")))
                val folder = File(noBackupFilesDir, "narrative-cache").apply { mkdirs() }
                val cache = android.util.AtomicFile(File(folder, "$indexedSourceHash-$analysisKey.json"))
                val cached = runCatching {
                    require(cache.baseFile.length() <= 2_000_000 && File(cache.baseFile.path + ".bak").length() <= 2_000_000)
                    cache.openRead().bufferedReader().use { it.readText() }.takeIf { it == preparedText } }
                    .getOrNull()?.takeIf { it.length <= 2_000_000 }?.let(NarrativeTimelineCodec::decode)
                if (cached != null && cached.assetSha256 == indexedSourceHash) { cacheHit = true; cached } else {
                    val stream = cache.startWrite()
                    try { stream.write(preparedText.toByteArray()); cache.finishWrite(stream) }
                    catch (failure: Exception) { cache.failWrite(stream); throw failure }
                    prepared
                }
            }.getOrNull()
            main.post {
                if (!destroyed) {
                    narrativeTimeline = loaded
                    narrativeCacheHit = cacheHit
                    narrativeStatus = if (loaded?.hasVerifiedSentences == true) "Cached sentence map · limited coverage" else "No eligible sentence map · conservative fallback"
                    publish()
                }
            }
        }
    }

    private fun cancelNarrativePause() {
        pendingNarrativePause = null
        main.removeCallbacks(narrativeTimer)
        coordinator.cancelScheduledPause()
    }

    /** Never calls Play/focus. Narrative pre-seeks on authorized Play; legacy modes use actual start. */
    private fun recoverFromTicket() {
        val id = audio.player.currentMediaItem?.mediaId.orEmpty()
        val position = recoveryTicket.consume(id, mediaRevision, audio.player.currentPosition) ?: return
        val floor = if (id == DemoCatalog.BOOK_ID && position >= 24_000) 24_000L else 0L
        val boundaries = if (!originalIndexVerified) emptyList() else when (id) {
            DemoCatalog.BOOK_ID -> acousticBoundaries
            DemoCatalog.CHAPTER_ONE_ID -> acousticBoundaries.filter { it < 24_000 }
            DemoCatalog.CHAPTER_TWO_ID -> acousticBoundaries.filter { it >= 24_000 }.map { it - 24_000 }
            else -> emptyList()
        }
        val narrativeMode = recoveryMode == RecoveryMode.NARRATIVE
        // Late focus rejection before any recovered audio starts cannot create another
        // rewind ticket from the prepared anchor. Actual isPlaying releases this guard.
        if (narrativeMode) narrativeRecoveryPrepared = true
        val heardFloor = if (narrativeMode) maxOf(floor, narrativeListenFloorMs ?: position) else floor
        val selection = narrativeSelection(position)
        val completed = recoveryAtCompletedSentence
        recoveryAtCompletedSentence = false
        recoverySentenceEndTarget = null
        val anchor = if (narrativeMode && !completed && narrativeReady() && selection != null) narrativeTimeline?.recovery(
            selection.absolutePositionMs, selection.chapterId, heardFromPositionMs = heardFloor + selection.offsetMs) else null
        val decision = if (narrativeMode && completed) RecoveryDecision(position, RecoveryStrategy.EXACT, 0)
        else if (anchor != null && selection != null) RecoveryDecision(anchor.positionMs - selection.offsetMs,
            RecoveryStrategy.NARRATIVE_SENTENCE, position - (anchor.positionMs - selection.offsetMs))
        else recoveryPlanner.plan(position, audio.player.duration, heardFloor, boundaries,
            if (narrativeMode) RecoveryMode.NARRATIVE else recoveryMode)
        if (narrativeMode && decision.strategy != RecoveryStrategy.INVALID_CONTEXT)
            narrativeListenFloorMs = maxOf(narrativeListenFloorMs ?: decision.targetPositionMs, decision.targetPositionMs)
        lastRecoveryTargetMs = decision.targetPositionMs
        lastNarrativeAnchor = anchor?.annotationId ?: if (completed) lastNarrativeAnchor else ""
        lastRecovery = "${decision.strategy} · replay ${decision.replayMs} ms${if (completed) " · completed sentence" else ""}"
        if (decision.strategy != RecoveryStrategy.INVALID_CONTEXT && decision.replayMs > 0) {
            recoverySeeks++
            audio.player.seekTo(decision.targetPositionMs)
            saveBookmark()
        }
        record("Recovery $lastRecovery; ${if (narrativeMode) "prepared before Media3 Play; output/focus still required" else "actual start authorized by existing intent/focus policy"}")
    }

    /** Explicit system media skip only. Never grants Play or bypasses interruption intent. */
    private fun skipChapter(forward: Boolean) {
        newSelection()
        intentGeneration++
        awaitingPlay = false
        cancelResolutions()
        when (audio.player.currentMediaItem?.mediaId) {
            DemoCatalog.BOOK_ID -> if (!forward || audio.player.currentPosition < 24_000) {
                audio.seek(if (forward) 24_000 else 0)
            }
            DemoCatalog.CHAPTER_ONE_ID -> if (forward) changeChapter(DemoCatalog.CHAPTER_TWO_ID)
            DemoCatalog.CHAPTER_TWO_ID -> if (!forward) changeChapter(DemoCatalog.CHAPTER_ONE_ID)
        }
        record("Explicit chapter ${if (forward) "next" else "previous"}; playback intent retained")
        facade?.refresh()
        publish()
    }

    private fun changeChapter(id: String) {
        val item = DemoCatalog.resolve(id, demoFile) ?: return
        // Keep the raw player's existing paused/playing intent; never issue a new Play.
        mediaRevision++
        audio.player.setMediaItem(item)
        audio.player.prepare()
    }

    private fun ensureDemo(): ListenableFuture<File> {
        if (demoFile.length() > 44) return Futures.immediateFuture(demoFile)
        preparation?.let { return it }
        val result = SettableFuture.create<File>()
        preparation = result
        val synthesizer = DemoSpeech(this)
        speech = synthesizer // Bundled preparation may complete synchronously.
        record("Preparing original spoken demo; bundled audio first")
        synthesizer.prepare { file, message ->
            record(message)
            preparation = null
            if (file != null) { verifyOriginalIndex(); result.set(file) } else result.setException(IllegalStateException(message))
            synthesizer.close()
            if (speech === synthesizer) {
                speech = null
            }
        }
        return result
    }

    private fun cancelResolutions() {
        resolutionGeneration++
        val cancelled = pendingResolutions.toList()
        pendingResolutions.clear()
        val wasCancelling = cancellingResolutions
        cancellingResolutions = true
        try { cancelled.forEach { it.cancel(false) } }
        finally { cancellingResolutions = wasCancelling }
    }

    private fun resolveItems(items: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        mediaError = ""
        val ids = items.map { it.mediaId.ifEmpty {
            it.requestMetadata.searchQuery?.let(::search)?.firstOrNull()?.mediaId ?: ""
        } }
        if (ids.any { it == LocalAudioStore.LOCAL_ID }) {
            val result = SettableFuture.create<List<MediaItem>>()
            val generation = resolutionGeneration
            pendingResolutions.add(result)
            io.execute {
                val resolved = runCatching {
                    ids.map { id ->
                        if (id == LocalAudioStore.LOCAL_ID) localStore.resolve() ?: error("Audio inaccessible")
                        else DemoCatalog.resolve(id, demoFile) ?: error("Unknown or unavailable media")
                    }
                }
                main.post {
                    pendingResolutions.remove(result)
                    if (destroyed || generation != resolutionGeneration || result.isCancelled) result.cancel(false)
                    else resolved.fold({ unavailableSelection = false; result.set(it) }, {
                        mediaError = "Audio unavailable or unsupported. Re-import it or select the demo."
                        interrupt("Local resolution failed")
                        val previous = cancellingResolutions
                        cancellingResolutions = true
                        try { result.setException(IllegalArgumentException("Audio inaccessible")) }
                        finally { cancellingResolutions = previous }
                        publish()
                    })
                }
            }
            return result
        }
        val generation = resolutionGeneration
        val delay = nextResolutionDelayMs
        nextResolutionDelayMs = 0
        val result = SettableFuture.create<List<MediaItem>>()
        pendingResolutions.add(result)
        val file = ensureDemo()
        file.addListener({
            val completion = Runnable {
                if (!destroyed && generation == resolutionGeneration && !result.isCancelled) {
                    pendingResolutions.remove(result)
                    try {
                        // Completion and Media3's post-or-run success callback share the
                        // application looper; a late legacy Play cannot outlive Pause.
                        val resolved = items.map {
                            val id = it.mediaId.ifEmpty {
                                it.requestMetadata.searchQuery?.let(DemoCatalog::search)?.firstOrNull()?.mediaId ?: ""
                            }
                            DemoCatalog.resolve(id, file.get())
                            ?: throw IllegalArgumentException("Unknown media ID") }
                        unavailableSelection = false
                        result.set(resolved)
                    } catch (failure: Exception) {
                        interrupt("Media resolution failed")
                        // A failed selection must not flush a queued Play onto the old item.
                        val wasCancelling = cancellingResolutions
                        cancellingResolutions = true
                        try { result.setException(failure) }
                        finally { cancellingResolutions = wasCancelling }
                    }
                } else result.cancel(false)
            }
            if (delay > 0) main.postDelayed(completion, delay) else completion.run()
        }, mainExecutorCompat())
        return result
    }

    private fun startSimulation() {
        stopSimulation(false)
        coordinator.resetNavigationCooldown()
        val generation = ++simulationGeneration
        val start = SystemClock.elapsedRealtime()
        record("SIMULATED fixture started in service")
        SimulatedNavigationSource().frames().forEach { frame ->
            simulation.postDelayed({
                navigation(frame.event.copy(elapsedMs = start + frame.event.elapsedMs, routeGeneration = generation))
            }, frame.offsetMs)
        }
    }

    private fun navigation(event: NavigationEvent) {
        val now = SystemClock.elapsedRealtime()
        val admission = RoadContextGate().evaluate(RoadContextObservation(ContextProvider.SIMULATOR,
            ContextDelivery.SIMULATED, event.elapsedMs, now, ContextValidity.VALID), now, debugBuild)
        if (!admission.eligible) {
            cancelNarrativePause()
            clearRecovery()
            apply(coordinator.contextRejected(admission.reason == ContextAdmissionReason.STALE), "Context rejected: ${admission.reason}")
            return
        }
        val lifecycle = event.signal != NavigationSignal.UPDATE || !event.routeActive ||
            event.maneuverEtaSeconds == null || event.maneuverEtaSeconds < 0 ||
            event.routeGeneration != lastAcceptedNavigationGeneration
        if (lifecycle) { cancelNarrativePause(); clearRecovery() }
        lastAcceptedNavigationGeneration = event.routeGeneration
        val selected = narrativeSelection()
        val plan = if (recoveryMode == RecoveryMode.NARRATIVE && narrativeReady() && audio.player.isPlaying &&
            coordinator.state == "NORMAL" && event.signal == NavigationSignal.UPDATE && event.routeActive &&
            event.maneuverEtaSeconds in 2..8 && selected != null) {
            narrativeOrchestrator.propose(narrativeTimeline!!, selected.chapterId, selected.absolutePositionMs,
                now, event.elapsedMs, event.maneuverEtaSeconds, event.routeGeneration, mediaRevision, admission.eligible)
        } else null
        routeLabel = "SIMULATED last input · ${event.signal} · generation=${event.routeGeneration} · active=${event.routeActive} · maneuver ETA=${event.maneuverEtaSeconds ?: "none"} s"
        val action = coordinator.onNavigation(event, now, deferAutomaticPause = plan != null)
        if (coordinator.state == "PAUSE_PENDING") {
            // Never extend an existing deadline on repeated observations.
            if (pendingNarrativePause == null && plan != null) {
                pendingNarrativePause = plan
                record("SIMULATED soft pause planned at ${plan.targetMs}ms / ${plan.annotationId}; no safety audio delayed")
                main.post(narrativeTimer)
            }
            pendingNarrativePause?.let { pending ->
                val update = narrativeOrchestrator.update(pending, selected?.absolutePositionMs ?: -1,
                    now, event.elapsedMs, event.maneuverEtaSeconds, event.routeGeneration, mediaRevision,
                    admitted = coordinator.navigationHealth == "AVAILABLE")
                when (update.decision) {
                    SoftPauseDecision.WAIT -> pendingNarrativePause = update.plan
                    SoftPauseDecision.CANCEL -> cancelNarrativePause()
                    SoftPauseDecision.PAUSE_NOW -> {
                        pendingNarrativePause = null; main.removeCallbacks(narrativeTimer)
                        apply(coordinator.commitScheduledPause(), "SIMULATED updated deadline overrides narrative wait")
                    }
                }
            }
        } else if (pendingNarrativePause != null) cancelNarrativePause()
        apply(action, "SIMULATED ${event.signal} generation=${event.routeGeneration} ETA=${event.maneuverEtaSeconds}")
    }

    private fun stopSimulation(cancelIntent: Boolean = true) {
        cancelNarrativePause()
        simulation.removeCallbacksAndMessages(null)
        if (cancelIntent || coordinator.state == "AUTO_PAUSED") interrupt("Simulation stopped/restarted")
        val now = SystemClock.elapsedRealtime()
        coordinator.onNavigation(NavigationEvent(now, false, null, routeGeneration = ++simulationGeneration,
            signal = NavigationSignal.CANCELLED), now)
        routeLabel = "SIMULATED source idle — no route"
    }

    private fun saveBookmark() {
        lastSaveMs = SystemClock.elapsedRealtime()
        val id = audio.player.currentMediaItem?.mediaId ?: return
        val identity = if (id == LocalAudioStore.LOCAL_ID)
            audio.player.currentMediaItem?.mediaMetadata?.extras?.getString(LocalAudioStore.CONTENT_IDENTITY) else null
        val value = PlaybackBookmark(id, audio.player.currentPosition.coerceAtLeast(0), identity)
        io.execute {
            try { bookmarks.write(value) }
            catch (failure: Exception) { Log.e("QuietChapter", "Bookmark write failed", failure) }
        }
    }

    private fun record(message: String) {
        entries.addLast("${SystemClock.elapsedRealtime()} $message")
        while (entries.size > 30) entries.removeFirst()
        Log.i("QuietChapter", message)
    }

    private fun snapshot() = Bundle().apply {
        putBoolean("narrativeCacheHit", narrativeCacheHit)
        putString("narrativeAnalysisVersion", narrativeTimeline?.analysisVersion)
        putString("narrativeStatus", narrativeStatus)
        putBoolean("narrativeReady", narrativeReady())
        putString("narrativeAnchor", lastNarrativeAnchor)
        putBoolean("pausedAtSentenceEnd", recoveryAtCompletedSentence)
        putLong("recoveryTargetMs", lastRecoveryTargetMs)
        putLong("narrativeListenFloorMs", narrativeListenFloorMs ?: -1)
        putLong("softPauseTargetMs", pendingNarrativePause?.targetMs ?: -1)
        putLong("softPauseDeadlineMs", pendingNarrativePause?.deadlineElapsedMs ?: -1)
        putBoolean("softPausePending", pendingNarrativePause != null)
        putString("platformContext", lastPlatformContext)
        putString("recoveryMode", recoveryMode.name)
        putString("lastRecovery", lastRecovery)
        putBoolean("recoveryPending", recoveryTicket.pending)
        putInt("recoverySeeks", recoverySeeks)
        putBoolean("acousticIndexVerified", originalIndexVerified)
        putString("contextMode", if (coordinator.navigationHealth == "AVAILABLE") "SIMULATED NAVIGATION" else "Basic playback · native route unavailable")
        putString("state", coordinator.state)
        putString("route", "$routeLabel · health=${coordinator.navigationHealth}")
        putString("navigationHealth", coordinator.navigationHealth)
        putLong("simulationGeneration", simulationGeneration)
        putString("log", entries.joinToString("\n"))
        putString("serviceInstance", instance)
        putInt("playerIdentity", System.identityHashCode(audio.player))
        putInt("actualStarts", actualStarts)
        putBoolean("awaitingPlay", awaitingPlay)
        putInt("pendingResolutions", pendingResolutions.size)
        putInt("mediaRevision", mediaRevision)
        putString("contentIdentity", audio.player.currentMediaItem?.mediaMetadata?.extras?.getString(LocalAudioStore.CONTENT_IDENTITY))
        putString("mediaId", audio.player.currentMediaItem?.mediaId ?: "")
        putBoolean("actualPlaying", audio.player.isPlaying)
        putBoolean("actualPlayWhenReady", audio.player.playWhenReady)
        putInt("actualPlaybackState", audio.player.playbackState)
        putString("mediaError", mediaError)
        putBoolean("loadingLocal", loadingLocal)
        putBoolean("localSelected", audio.player.currentMediaItem?.mediaId == LocalAudioStore.LOCAL_ID)
        putBoolean("localAvailable", localAudio != null)
        putBoolean("localPersistent", localAudio?.persistentRead == true)
        putString("localTitle", localAudio?.title)
        putString("selectedTitle", audio.player.currentMediaItem?.mediaMetadata?.title?.toString())
        putBoolean("importing", importing)
        putBoolean("testClockFault", SystemClock.elapsedRealtime() < debugFrozenUntilMs)
        putBoolean("demoReady", demoFile.length() > 44)
        putLong("positionMs", audio.player.currentPosition)
        putLong("durationMs", audio.player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0)
    }

    private fun publish() {
        if (!destroyed && ::audio.isInitialized) session?.setSessionExtras(snapshot())
    }

    private fun mainExecutorCompat() = java.util.concurrent.Executor { action -> main.post(action) }

    private fun catalogItem(id: String): MediaItem? =
        if (id == LocalAudioStore.LOCAL_ID) localAudio?.toMediaItem() else DemoCatalog.item(id)

    private fun search(query: String): List<MediaItem> = DemoCatalog.search(query) +
        listOfNotNull(localAudio?.takeIf { query.isNotBlank() && it.title.contains(query.trim(), true) }?.toMediaItem())

    /** Only the parked Activity can offer a document; this command is restricted to our UID. */
    private fun importAudio(args: Bundle): ListenableFuture<SessionResult> {
        if (importing) return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val uri = args.getString("uri")?.let(android.net.Uri::parse)
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        pause()
        // Validation must not discard a good paused book or its position.
        importing = true
        mediaError = ""
        val generation = selectionGeneration
        val result = SettableFuture.create<SessionResult>()
        io.execute {
            val imported = runCatching {
                // Registration identity makes an older/failed bookmark harmless on restart.
                localStore.import(uri, args.getInt("flags"))
            }
            main.post {
                importing = false
                if (destroyed) { result.cancel(false); return@post }
                imported.fold({ selected ->
                    registrationGeneration++
                    localAudio = selected
                    if (generation == selectionGeneration) {
                        newSelection()
                        unavailableSelection = false
                        mediaRevision++
                        audio.player.setMediaItem(selected.toMediaItem())
                        audio.player.prepare()
                        // Clearing an old playlist may report ENDED. New selection is paused.
                        apply(coordinator.userPause(), "Imported selection ready")
                        saveBookmark()
                    }
                    record("Local document validated; paused selection; persistentRead=${selected.persistentRead}")
                    publish()
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS, snapshot()))
                }, {
                    mediaError = "Cannot import this audio. Choose a readable audio file or use the original demo."
                    record("Local document import failed; no playback")
                    publish()
                    result.set(SessionResult(SessionError.ERROR_BAD_VALUE, snapshot()))
                })
            }
        }
        publish()
        return result
    }

    private fun traceLibrary(kind: String, controller: MediaSession.ControllerInfo) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            Log.d("QuietInput", "${SystemClock.elapsedRealtime()} library=$kind package=${controller.packageName} uid=${controller.uid}")
    }

    private inner class LibraryCallbacks : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val own = controller.uid == Process.myUid()
            record("Controller package=${controller.packageName} uid=${controller.uid} trusted=${controller.isTrusted} own=$own")
            if (!own && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            if (own) QuietCommands.all.filter { debugBuild || it !in QuietCommands.debugOnly }.forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS).build()
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(DemoCatalog.item(DemoCatalog.ROOT_ID)!!, params)).also { traceLibrary("root", browser) }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(catalogItem(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            traceLibrary("children", browser)
            if (catalogItem(parentId) == null || page < 0 || pageSize < 1) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
            val children = DemoCatalog.children(parentId) + if (parentId == DemoCatalog.AUDIOBOOKS_ID) listOfNotNull(localAudio?.toMediaItem()) else emptyList()
            val from = (page.toLong() * pageSize).coerceAtMost(children.size.toLong()).toInt()
            val until = (from.toLong() + pageSize).coerceAtMost(children.size.toLong()).toInt()
            return Futures.immediateFuture(LibraryResult.ofItemList(children.subList(from, until), params))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, search(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (page < 0 || pageSize < 1) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            val matches = search(query)
            val start = (page.toLong() * pageSize).coerceAtMost(matches.size.toLong()).toInt()
            val end = (start.toLong() + pageSize).coerceAtMost(matches.size.toLong()).toInt()
            return Futures.immediateFuture(LibraryResult.ofItemList(matches.subList(start, end), params))
        }

        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
            resolveItems(mediaItems)

        override fun onMediaButtonEvent(session: MediaSession, controller: MediaSession.ControllerInfo, intent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            if (key.action == KeyEvent.ACTION_DOWN && coordinator.state == "AUTO_PAUSED" &&
                key.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)) {
                pause()
                return true
            }
            return false
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.uid != Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            if (!debugBuild && command.customAction in QuietCommands.debugOnly)
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            when (command.customAction) {
                QuietCommands.RECOVERY_MODE -> {
                    val selected = RecoveryMode.entries.firstOrNull { it.name == args.getString("mode") }
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    cancelNarrativePause()
                    clearRecovery()
                    recoveryMode = selected
                    getSharedPreferences("recovery", MODE_PRIVATE).edit().putString("mode", selected.name).apply()
                    lastRecovery = "${selected.name} · explicit opt-in; no playback started"
                }
                QuietCommands.DEBUG_FREEZE_CLOCK -> {
                    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0)
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    debugFrozenPositionMs = audio.player.currentPosition
                    debugFrozenUntilMs = SystemClock.elapsedRealtime() + 10_000
                    progressGuard.reset()
                    record("TEST_ONLY frozen progress sample; actual renderer position is unchanged")
                }
                QuietCommands.IMPORT_AUDIO -> return importAudio(args)
                QuietCommands.PREPARE -> {
                    mediaError = ""
                    newSelection()
                    val prepareGeneration = ++intentGeneration
                    awaitingPlay = false
                    cancelResolutions()
                    val ready = ensureDemo()
                    return Futures.transform(ready, { file ->
                        if (!destroyed && !importing && prepareGeneration == intentGeneration && audio.player.mediaItemCount == 0) {
                            val saved = bookmarks.read()
                            audio.player.setMediaItem(DemoCatalog.resolve(saved?.mediaId?.takeIf { it != LocalAudioStore.LOCAL_ID } ?: DemoCatalog.BOOK_ID, file!!)!!)
                            unavailableSelection = false
                            val savedPosition = saved?.takeIf { it.mediaId != LocalAudioStore.LOCAL_ID }?.positionMs ?: 0
                            restoringPosition = savedPosition
                            audio.player.seekTo(savedPosition)
                            audio.player.prepare()
                        }
                        publish()
                        SessionResult(SessionResult.RESULT_SUCCESS, snapshot())
                    }, mainExecutorCompat())
                }
                QuietCommands.SIMULATE -> startSimulation()
                QuietCommands.STOP_SIMULATION -> stopSimulation()
                QuietCommands.SIMULATED_EVENT -> {
                    val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                    val generation = if (debug) args.getLong("generation", simulationGeneration) else simulationGeneration
                    val signal = if (debug) NavigationSignal.entries.firstOrNull { it.name == args.getString("signal") }
                        ?: NavigationSignal.UPDATE else NavigationSignal.UPDATE
                    navigation(NavigationEvent(
                        if (debug) args.getLong("sourceElapsedMs", SystemClock.elapsedRealtime()) else SystemClock.elapsedRealtime(),
                        args.getBoolean("active", true), if (args.containsKey("eta")) args.getInt("eta") else null,
                        routeGeneration = generation, signal = signal))
                    simulationGeneration = maxOf(simulationGeneration, generation)
                }
                QuietCommands.SNAPSHOT -> Unit
                QuietCommands.DELAY_NEXT_PREPARATION, QuietCommands.DELAY_NEXT_RESOLUTION -> {
                    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    val delay = args.getLong("delayMs").coerceIn(0, 15_000)
                    if (command.customAction == QuietCommands.DELAY_NEXT_PREPARATION) nextPreparationDelayMs = delay
                    else nextResolutionDelayMs = delay
                }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            publish()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, snapshot()))
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        record("Task removed; actualPlaying=${audio.player.isPlaying}")
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            interrupt("Automotive task removed")
            stopSimulation(false)
            audio.player.stop()
            saveBookmark()
            stopSelf()
        } else if (!audio.player.isPlaying) {
            interrupt("Task removed while not playing")
            stopSimulation(false)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cancelNarrativePause()
        saveBookmark()
        destroyed = true
        intentGeneration++
        cancelResolutions()
        main.removeCallbacksAndMessages(null)
        simulation.removeCallbacksAndMessages(null)
        preparation?.cancel(false)
        speech?.close()
        session?.release()
        session = null
        // ForwardingSimpleBasePlayer releases the sole underlying ExoPlayer once.
        facade?.release()
        facade = null
        io.shutdown()
        Log.i("QuietChapter", "Service destroyed $instance")
        super.onDestroy()
    }
}

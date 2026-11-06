package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import com.quietchapter.media.DemoCatalog
import java.util.concurrent.TimeUnit
import java.io.File

class ServiceFoundationSuite(private val test: Instrumentation) {
    private lateinit var activity: MainActivity
    private var client: ServiceTestClient? = null

    fun run(mode: String, oldInstance: String? = null, expectedId: String? = null, expectedPosition: Long? = null) {
        try {
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            client = ServiceTestClient(test)
            val c = client!!
            when (mode) {
                "foundation" -> foundation(c)
                "resilience" -> NavigationResilienceSuite(test, c).run()
                "death_prepare" -> deathPrepare(c)
                "death_verify" -> deathVerify(c, oldInstance, expectedId, expectedPosition)
                "calls" -> { client = ServicePhoneSuite(test, activity, c).run() }
                else -> error("Unknown service test mode")
            }
            if (mode != "death_prepare") {
                client?.pause()
                report("QuietChapter service $mode PASS")
                test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Service $mode PASS\n") })
            }
        } catch (failure: Throwable) {
            report("FAIL ${failure.message}")
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Service FAIL: ${failure.stackTraceToString()}\n") })
        } finally { client?.close() }
    }

    private fun foundation(c: ServiceTestClient) {
        c.prepare()
        c.pause()
        c.command(QuietCommands.STOP_SIMULATION)
        val root = c.request { c.browser.getLibraryRoot(null) }
        check(root.value?.mediaId == DemoCatalog.ROOT_ID)
        val tabs = c.request { c.browser.getChildren(DemoCatalog.ROOT_ID, 0, 10, null) }.value!!
        check(tabs.size == 1 && tabs[0].mediaId == DemoCatalog.AUDIOBOOKS_ID)
        check(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
        val books = c.request { c.browser.getChildren(DemoCatalog.AUDIOBOOKS_ID, 0, 10, null) }.value!!
        check(books.any { it.mediaId == DemoCatalog.BOOK_ID })
        val chapters = c.request { c.browser.getChildren(DemoCatalog.BOOK_ID, 0, 1, null) }.value!!
        val second = c.request { c.browser.getChildren(DemoCatalog.BOOK_ID, 1, 1, null) }.value!!
        check(chapters[0].mediaId == DemoCatalog.CHAPTER_ONE_ID && second[0].mediaId == DemoCatalog.CHAPTER_TWO_ID)
        check(c.request { c.browser.getItem("unknown-id") }.resultCode != 0)
        c.main {
            c.browser.setMediaItem(MediaItem.Builder().setMediaId(DemoCatalog.CHAPTER_ONE_ID)
                .setUri("file:///must-not-be-used.wav").build())
            c.browser.prepare()
        }
        await("resolved chapter duration") { c.snapshot().getLong("durationMs") in 23_000..24_100 }
        report("PASS browsable root/book/chapters, pagination, unknown ID rejection and trusted ID-to-local-file resolution")
        c.select(DemoCatalog.BOOK_ID)
        c.seek(0)
        c.play()
        await("service playback") { c.snapshot().getBoolean("actualPlaying") }
        val first = c.snapshot()
        val position = first.getLong("positionMs")
        val nm = test.targetContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        await("foreground media notification") {
            nm.activeNotifications.any { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 &&
                it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
        }
        c.main { activity.finish() }
        SystemClock.sleep(1_500)
        val background = c.snapshot()
        val automotiveTaskRemoved = test.targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE) &&
            background.getString("log").orEmpty().contains("Task removed;")
        if (automotiveTaskRemoved) {
            check(!background.getBoolean("actualPlayWhenReady") && background.getInt("actualPlaybackState")==androidx.media3.common.Player.STATE_IDLE)
            c.play();await("explicit Play after automotive task removal") { c.snapshot().getBoolean("actualPlaying") }
        } else check(background.getBoolean("actualPlaying") && background.getLong("positionMs") > position + 500)
        activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val reopened = c.snapshot()
        check(reopened.getString("serviceInstance") == first.getString("serviceInstance"))
        check(reopened.getInt("playerIdentity") == first.getInt("playerIdentity"))
        report("PASS foreground notification and Activity close/reopen share one service/player; Automotive task removal stops, phone/background retains playback")

        c.pause()
        val pendingStarts = c.snapshot().getInt("actualStarts")
        c.command(QuietCommands.DELAY_NEXT_RESOLUTION, Bundle().apply { putLong("delayMs", 2_000) })
        val noteForLegacy = nm.activeNotifications.first { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }.notification
        @Suppress("DEPRECATION")
        val legacyToken = noteForLegacy.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!
        MediaController(test.targetContext, legacyToken).transportControls.playFromMediaId(DemoCatalog.BOOK_ID, null)
        await("pending legacy media resolution") { c.snapshot().getInt("pendingResolutions") > 0 }
        c.pause()
        SystemClock.sleep(2_500)
        check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getInt("actualStarts") == pendingStarts)
        report("PASS a second controller Pause cancels delayed legacy playFromMediaId resolution without a late Play")
        ServiceTestClient(test).use { other ->
            c.command(QuietCommands.DELAY_NEXT_RESOLUTION, Bundle().apply { putLong("delayMs", 2_000) })
            other.main {
                other.browser.setMediaItem(MediaItem.Builder().setMediaId(DemoCatalog.BOOK_ID).build())
                other.browser.prepare()
                other.browser.play()
            }
            await("pending modern media resolution") { c.snapshot().getInt("pendingResolutions") > 0 }
            c.pause()
            SystemClock.sleep(2_500)
            check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getInt("actualStarts") == pendingStarts)
            report("PASS two modern controllers: cancelling pending SetMediaItem+Play cannot reenter Play during another controller Pause")
        }
        ServiceTestClient(test).use { other ->
            other.main {
                other.browser.setMediaItem(MediaItem.Builder().setMediaId("unknown-id").setUri("file:///untrusted.wav").build())
                other.browser.play()
            }
            await("invalid selection failure") { c.snapshot().getString("log")!!.contains("Media resolution failed") }
            SystemClock.sleep(500)
            check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getInt("actualStarts") == pendingStarts)
            report("PASS invalid media selection cannot flush queued Play onto previous content")
        }

        for (surface in listOf("browser", "platform", "media_key")) {
            c.command(QuietCommands.STOP_SIMULATION)
            c.play()
            await("playing before $surface") { c.snapshot().getBoolean("actualPlaying") }
            SystemClock.sleep(10)
            c.navigation(5)
            await("automatic pause before $surface") { c.snapshot().getString("state") == "AUTO_PAUSED" }
            val starts = c.snapshot().getInt("actualStarts")
            when (surface) {
                "browser" -> c.pause()
                "platform" -> {
                    val note = nm.activeNotifications.first { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }.notification
                    @Suppress("DEPRECATION")
                    val token = note.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!
                    MediaController(test.targetContext, token).transportControls.pause()
                }
                else -> shell("input keyevent KEYCODE_MEDIA_PAUSE")
            }
            await("manual Pause from $surface") { c.snapshot().getString("state") == "USER_PAUSED" }
            clear(c)
            repeat(10) {
                val state = c.snapshot()
                check(!state.getBoolean("actualPlaying") && state.getInt("actualStarts") == starts)
                SystemClock.sleep(50)
            }
            report("PASS standard $surface Pause during automatic pause cancels route resume")
        }
        c.command(QuietCommands.STOP_SIMULATION)
        c.play()
        await("playing before completion seek") { c.snapshot().getBoolean("actualPlaying") }
        c.seek(c.snapshot().getLong("durationMs"))
        await("completion clears intent") { c.snapshot().getString("state") == "ENDED" && !c.snapshot().getBoolean("actualPlayWhenReady") }
        val completedStarts = c.snapshot().getInt("actualStarts")
        c.main { c.browser.seekBack() }
        c.seek(0)
        SystemClock.sleep(500)
        check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getInt("actualStarts") == completedStarts)
        report("PASS completion then standard rewind/seek stays paused without bypassing Play eligibility")
        c.command(QuietCommands.STOP_SIMULATION)
        c.seek(12_000)
        c.pause()
        SystemClock.sleep(1_500)
        val saved = c.snapshot()
        c.close()
        client = null
        test.runOnMainSync { activity.finish() }
        await("finished Activity destroyed") { activity.isDestroyed }
        SystemClock.sleep(500)
        report("LIFECYCLE_STEP stopservice requested")
        test.targetContext.stopService(Intent(test.targetContext, PlaybackService::class.java))
        report("LIFECYCLE_STEP stopservice returned")
        SystemClock.sleep(700)
        activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        report("LIFECYCLE_STEP restarted Activity returned")
        val restored = ServiceTestClient(test)
        client = restored
        val state = restored.snapshot()
        check(state.getString("serviceInstance") != saved.getString("serviceInstance")) { "Service did not restart" }
        check(!state.getBoolean("actualPlaying") && !state.getBoolean("actualPlayWhenReady"))
        check(state.getLong("positionMs") in 11_000..13_000)
        report("PASS service stop/restart restores bounded media position paused with no automatic-resume intent")
        restored.close()
        client = null
        test.runOnMainSync { activity.finish() }
        await("finished Activity destroyed") { activity.isDestroyed }
        SystemClock.sleep(500)
        report("LIFECYCLE_STEP stopservice requested")
        test.targetContext.stopService(Intent(test.targetContext, PlaybackService::class.java))
        report("LIFECYCLE_STEP stopservice returned")
        SystemClock.sleep(700)
        val media = File(test.targetContext.noBackupFilesDir, "quietchapter-original-v1.wav")
        val backup = File(test.targetContext.noBackupFilesDir, "quietchapter-original-v1.missing-test.wav")
        check(media.renameTo(backup))
        try {
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val missing = ServiceTestClient(test)
            client = missing
            check(!missing.snapshot().getBoolean("demoReady") && !missing.snapshot().getBoolean("actualPlaying"))
            val missingState = missing.snapshot()
            report("LIFECYCLE_STEP missing-source snapshot=$missingState")
            check(missingState.getString("mediaId") == "" && missingState.getInt("actualStarts") == 0)
            check(backup.renameTo(media))
            missing.prepare()
            check(!missing.snapshot().getBoolean("actualPlaying") && !missing.snapshot().getBoolean("actualPlayWhenReady"))
            report("PASS missing durable media restores unprepared and silent; explicit preparation recovers without autoplay")
        } finally { if (backup.exists() && !media.exists()) backup.renameTo(media) }
    }

    private fun deathPrepare(c: ServiceTestClient) {
        c.prepare()
        c.command(QuietCommands.STOP_SIMULATION)
        c.select(DemoCatalog.BOOK_ID)
        c.seek(12_000)
        c.play()
        await("playing before process death") { c.snapshot().getBoolean("actualPlaying") && c.snapshot().getLong("positionMs") >= 13_000 }
        SystemClock.sleep(1_500)
        report("HOST_KILL_READY ${c.snapshot().getString("serviceInstance")}")
        SystemClock.sleep(30_000)
        error("Host did not kill process")
    }

    private fun deathVerify(c: ServiceTestClient, oldInstance: String?, expectedId: String?, expectedPosition: Long?) {
        val state = c.snapshot()
        check(oldInstance != null && state.getString("serviceInstance") != oldInstance)
        check(!state.getBoolean("actualPlaying") && !state.getBoolean("actualPlayWhenReady") && state.getInt("actualStarts") == 0)
        check(expectedPosition != null && expectedId != null)
        check(state.getString("mediaId") == expectedId)
        check(kotlin.math.abs(state.getLong("positionMs") - expectedPosition) <= 150) { "Restoration differs from captured durable bookmark" }
        check(state.getString("route")!!.contains("idle"))
        c.play()
        await("explicit recovery after process death") { c.snapshot().getBoolean("actualPlaying") }
        report("PASS real process death restores position only, no playback/navigation latch, explicit Play recovers")
    }

    private fun clear(c: ServiceTestClient) {
        c.navigation(60)
        SystemClock.sleep(2_200)
        c.navigation(60)
    }
    private fun await(description: String, timeoutMs: Long = 8_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        error("Timed out: $description")
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun report(message: String) {
        Log.i("QuietChapterServiceTest", message)
        test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
}

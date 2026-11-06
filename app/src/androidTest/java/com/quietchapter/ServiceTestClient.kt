package com.quietchapter

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit

/** Real session client; waits only on the instrumentation thread, never on the main looper. */
class ServiceTestClient(private val test: Instrumentation) : AutoCloseable {
    val browser: MediaBrowser
    init {
        var future: ListenableFuture<MediaBrowser>? = null
        main { future = MediaBrowser.Builder(test.targetContext,
            SessionToken(test.targetContext, ComponentName(test.targetContext, PlaybackService::class.java))).buildAsync() }
        browser = future!!.get(15, TimeUnit.SECONDS)
    }
    fun command(action: String, args: Bundle = Bundle.EMPTY): Bundle {
        var future: ListenableFuture<SessionResult>? = null
        main { future = browser.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
        val result = future!!.get(30, TimeUnit.SECONDS)
        check(result.resultCode == SessionResult.RESULT_SUCCESS) { "Command $action failed: ${result.resultCode}" }
        return result.extras
    }
    fun snapshot() = command(QuietCommands.SNAPSHOT)
    fun <T> request(block: () -> ListenableFuture<T>): T {
        var future: ListenableFuture<T>? = null
        main { future = block() }
        return future!!.get(15, TimeUnit.SECONDS)
    }
    fun prepare() = command(QuietCommands.PREPARE)
    fun play() = main { browser.play() }
    fun pause() = main { browser.pause() }
    fun seek(position: Long) = main { browser.seekTo(position) }
    fun select(id: String) {
        val revision = snapshot().getInt("mediaRevision")
        main { browser.setMediaItem(MediaItem.Builder().setMediaId(id).build()); browser.prepare() }
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val state = snapshot()
            if (state.getInt("mediaRevision") > revision && state.getString("mediaId") == id && state.getLong("durationMs") > 0) return
            android.os.SystemClock.sleep(50)
        }
        error("Media selection did not resolve: $id")
    }
    fun navigation(eta: Int) = command(QuietCommands.SIMULATED_EVENT, Bundle().apply { putInt("eta", eta) })
    override fun close() = main { browser.release() }
    fun main(block: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}

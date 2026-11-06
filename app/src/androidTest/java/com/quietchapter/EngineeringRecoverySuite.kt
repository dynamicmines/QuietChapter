package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.quietchapter.media.DemoCatalog
import com.quietchapter.recovery.*
import org.json.JSONObject

/** SIMULATED navigation drives the real service/player. No SDK payload is fabricated. */
class EngineeringRecoverySuite(private val test: Instrumentation) {
    private var generation = 0L
    private val boundaries = listOf(210L,1810,4950,9920,11480,12960,16790,18080,18990,20650,22900,23930,25240,26990,30510,31860,34770,36940,39980,42110)
    fun run(mode: String = "recovery") {
        try {
            if (mode == "recovery") test.startActivitySync(android.content.Intent(test.targetContext,MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            ServiceTestClient(test).use { c ->
                if (mode == "release_gate") {
                    check(test.targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0)
                    val activity=test.startActivitySync(android.content.Intent(test.targetContext,MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    c.main {
                        check(activity.findViewById<android.view.View>(R.id.demo_toggle).visibility==android.view.View.GONE)
                        for (action in QuietCommands.debugOnly) check(!c.browser.availableSessionCommands.contains(androidx.media3.session.SessionCommand(action,Bundle.EMPTY)))
                    }
                    val before=c.snapshot();var rejected=false
                    try {
                        val denied=c.request { c.browser.sendCustomCommand(androidx.media3.session.SessionCommand(QuietCommands.SIMULATE,Bundle.EMPTY),Bundle.EMPTY) }
                        rejected=denied.resultCode!=androidx.media3.session.SessionResult.RESULT_SUCCESS
                    } catch (_:IllegalStateException) { rejected=true }
                    check(rejected)
                    val after=c.snapshot();check(after.getLong("simulationGeneration")==before.getLong("simulationGeneration") && !after.getBoolean("actualPlayWhenReady"))
                    c.prepare();c.select(DemoCatalog.CHAPTER_TWO_ID);c.play();await("release explicit Play") { c.snapshot().getBoolean("actualPlaying") };c.pause()
                    report("release_gate_rejected_simulation_standard_play_works",c,provenance="ACTUAL_RELEASE_CONTROLLER_NO_NAVIGATION")
                    test.finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Engineering release_gate PASS\n") });return
                }
                if (mode == "persist_prepare") {
                    c.prepare(); c.pause(); c.command(QuietCommands.STOP_SIMULATION)
                    c.command(QuietCommands.RECOVERY_MODE,Bundle().apply { putString("mode","ACOUSTIC") })
                    c.select(DemoCatalog.CHAPTER_TWO_ID);c.seek(7600);c.pause();SystemClock.sleep(1300)
                    report("persist_prepared_paused",c,provenance="ACTUAL_SERVICE_PERSISTENCE_NO_NAVIGATION")
                    test.finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Engineering persist_prepare PASS\n") });return
                }
                if (mode == "persist_verify") {
                    val s=c.snapshot();check(s.getString("recoveryMode")=="ACOUSTIC")
                    check(s.getString("mediaId")==DemoCatalog.CHAPTER_TWO_ID && s.getLong("positionMs") in 7450..7750)
                    check(!s.getBoolean("actualPlayWhenReady") && !s.getBoolean("recoveryPending"))
                    c.play();await("restored explicit play") { c.snapshot().getBoolean("actualPlaying") }
                    check(c.snapshot().getInt("recoverySeeks")==0);report("persist_verified_explicit_only",c,provenance="ACTUAL_SERVICE_PERSISTENCE_NO_NAVIGATION");c.pause()
                    c.command(QuietCommands.RECOVERY_MODE,Bundle().apply { putString("mode","EXACT") })
                    test.finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Engineering persist_verify PASS\n") });return
                }
                val originalMode = c.snapshot().getString("recoveryMode") ?: "EXACT"
                try {
                    c.prepare()
                    await("verified original index") { c.snapshot().getBoolean("acousticIndexVerified") }
                    for (mode in listOf(RecoveryMode.EXACT, RecoveryMode.FIXED_BACKTRACK, RecoveryMode.ACOUSTIC)) {
                        reset(c,mode); c.seek(14_000); c.play()
                        await("playing") { c.snapshot().getBoolean("actualPlaying") }
                        send(c,5); await("automatic pause") { c.snapshot().getString("state")=="AUTO_PAUSED" }
                        val paused=c.snapshot(); val source=paused.getLong("positionMs");check(paused.getBoolean("recoveryPending"))
                        val expected=RecoveryPlanner().plan(source,45_373,boundariesMs=boundaries,mode=mode)
                        val seeks=paused.getInt("recoverySeeks")
                        clear(c)
                        await("authorized resume") { c.snapshot().getBoolean("actualPlaying") }
                        val resumed=c.snapshot();val observed=resumed.getLong("positionMs")
                        check(observed in expected.targetPositionMs..expected.targetPositionMs+900) { "Expected $expected observed=$observed" }
                        check(resumed.getInt("recoverySeeks")==seeks+if(expected.replayMs>0)1 else 0)
                        repeat(8) { send(c,60) }
                        check(c.snapshot().getInt("recoverySeeks")==resumed.getInt("recoverySeeks"))
                        report("comparison_${mode.name}",c,JSONObject().put("pauseMs",source).put("expectedTargetMs",expected.targetPositionMs).put("observedMs",observed).put("strategy",expected.strategy.name))
                    }
                    focusOrders(c)
                    reset(c,RecoveryMode.ACOUSTIC); c.seek(14_000); c.play(); await("manual playing") { c.snapshot().getBoolean("actualPlaying") };send(c,5)
                    await("manual auto pause") { c.snapshot().getString("state")=="AUTO_PAUSED" };c.pause();val seeks=c.snapshot().getInt("recoverySeeks");clear(c)
                    check(c.snapshot().getString("state")=="USER_PAUSED" && !c.snapshot().getBoolean("actualPlayWhenReady"))
                    check(!c.snapshot().getBoolean("recoveryPending") && c.snapshot().getInt("recoverySeeks")==seeks)
                    c.play();await("explicit after manual") { c.snapshot().getBoolean("actualPlaying") };check(c.snapshot().getInt("recoverySeeks")==seeks)
                    report("manual_pause_cancels_recovery",c)

                    reset(c,RecoveryMode.ACOUSTIC);c.seek(14_000);c.play();await("seek playing") { c.snapshot().getBoolean("actualPlaying") };send(c,5)
                    await("seek automatic pause") { c.snapshot().getString("state")=="AUTO_PAUSED" };c.seek(7_000);clear(c)
                    await("seek resumed") { c.snapshot().getBoolean("actualPlaying") };check(c.snapshot().getLong("positionMs") in 7000..7900)
                    check(!c.snapshot().getBoolean("recoveryPending"));report("manual_seek_cancels_recovery",c)

                    reset(c,RecoveryMode.ACOUSTIC);c.select(DemoCatalog.CHAPTER_TWO_ID);c.seek(1_000);c.play();await("chapter playing") { c.snapshot().getBoolean("actualPlaying") };send(c,5)
                    await("chapter paused") { c.snapshot().getString("state")=="AUTO_PAUSED" };clear(c)
                    await("chapter resumed") { c.snapshot().getBoolean("actualPlaying") }
                    check(c.snapshot().getString("mediaId")==DemoCatalog.CHAPTER_TWO_ID && c.snapshot().getLong("positionMs") in 0..1900)
                    report("clipped_chapter_coordinate",c)

                    reset(c,RecoveryMode.ACOUSTIC);c.seek(14_000);c.play();await("loss playing") { c.snapshot().getBoolean("actualPlaying") };send(c,5)
                    await("loss pause") { c.snapshot().getString("state")=="AUTO_PAUSED" };SystemClock.sleep(5400)
                    check(c.snapshot().getString("state")=="INTERRUPTED" && !c.snapshot().getBoolean("recoveryPending"))
                    clear(c);check(!c.snapshot().getBoolean("actualPlayWhenReady"));report("source_loss_explicit_recovery_required",c)
                    for (signal in listOf("REROUTING","CANCELLED","DISCONNECTED")) {
                        reset(c,RecoveryMode.ACOUSTIC);c.seek(14_000);c.play();await("lifecycle playing") { c.snapshot().getBoolean("actualPlaying") }
                        send(c,5);await("lifecycle pause") { c.snapshot().getString("state")=="AUTO_PAUSED" }
                        c.command(QuietCommands.SIMULATED_EVENT,Bundle().apply { putString("signal",signal);putLong("generation",generation);putLong("sourceElapsedMs",SystemClock.elapsedRealtime()) })
                        val lost=c.snapshot();check(!lost.getBoolean("recoveryPending") && !lost.getBoolean("actualPlayWhenReady"))
                        val count=lost.getInt("recoverySeeks");c.play();await("explicit lifecycle recovery") { c.snapshot().getBoolean("actualPlaying") }
                        check(c.snapshot().getInt("recoverySeeks")==count);report("${signal.lowercase()}_invalidates_rewind",c)
                    }
                } finally {
                    c.pause();c.command(QuietCommands.STOP_SIMULATION)
                    c.command(QuietCommands.RECOVERY_MODE,Bundle().apply { putString("mode",originalMode) })
                }
            }
            test.finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Engineering recovery PASS: 13 real-service scenario groups; navigation SIMULATED; sound assessed separately\n") })
        } catch(failure:Throwable) { test.finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","Engineering recovery FAIL: ${failure.stackTraceToString()}\n") }) }
    }
    private fun focusOrders(c:ServiceTestClient) {
        val manager = test.targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        for (navigationFirst in listOf(false,true)) {
            reset(c,RecoveryMode.ACOUSTIC);c.seek(14_000);c.play();await("focus playing") { c.snapshot().getBoolean("actualPlaying") }
            if (navigationFirst) { send(c,5);await("focus navigation pause") { c.snapshot().getString("state")=="AUTO_PAUSED" } }
            val request=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }.build()
            check(manager.requestAudioFocus(request)==android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            try {
                await("actual focus interruption") { c.snapshot().getString("state")=="INTERRUPTED" }
                if (!navigationFirst) send(c,5)
                clear(c);check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                val starts=c.snapshot().getInt("actualStarts");c.play();SystemClock.sleep(300)
                check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getInt("actualStarts")==starts)
                c.pause();report("focus_${if(navigationFirst)"navigation_first" else "focus_first"}_manual_priority",c)
            } finally { manager.abandonAudioFocusRequest(request) }
            SystemClock.sleep(300);check(!c.snapshot().getBoolean("actualPlayWhenReady") && !c.snapshot().getBoolean("recoveryPending"))
        }
        // A fresh interruption after an authorized start may recover only after explicit Play.
        reset(c,RecoveryMode.ACOUSTIC);c.seek(14_000);c.play();await("explicit focus playing") { c.snapshot().getBoolean("actualPlaying") }
        val request=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        check(manager.requestAudioFocus(request)==android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        try { await("explicit focus interruption") { c.snapshot().getString("state")=="INTERRUPTED" };check(c.snapshot().getBoolean("recoveryPending")) }
        finally { manager.abandonAudioFocusRequest(request) }
        SystemClock.sleep(400);check(!c.snapshot().getBoolean("actualPlayWhenReady"))
        val seeks=c.snapshot().getInt("recoverySeeks");c.play();await("explicit after focus") { c.snapshot().getBoolean("actualPlaying") };check(c.snapshot().getInt("recoverySeeks")==seeks+1);report("focus_recovery_explicit_only",c)
    }

    private fun reset(c:ServiceTestClient,mode:RecoveryMode) {
        c.pause();c.command(QuietCommands.STOP_SIMULATION);c.select(DemoCatalog.BOOK_ID)
        c.command(QuietCommands.RECOVERY_MODE,Bundle().apply { putString("mode",mode.name) })
        generation=c.snapshot().getLong("simulationGeneration")+1;send(c,60)
    }
    private fun clear(c:ServiceTestClient) { SystemClock.sleep(30);send(c,60);SystemClock.sleep(2100);send(c,60) }
    private fun send(c:ServiceTestClient,eta:Int) = c.command(QuietCommands.SIMULATED_EVENT,Bundle().apply { putInt("eta",eta);putLong("generation",generation);putLong("sourceElapsedMs",SystemClock.elapsedRealtime()) })
    private fun report(name:String,c:ServiceTestClient,extra:JSONObject=JSONObject(),provenance:String="SIMULATED_NAVIGATION_REAL_SERVICE") {
        val s=c.snapshot();extra.put("stage",name).put("deviceNs",SystemClock.elapsedRealtimeNanos()).put("positionMs",s.getLong("positionMs"))
            .put("state",s.getString("state")).put("mode",s.getString("recoveryMode")).put("lastRecovery",s.getString("lastRecovery")).put("playerIdentity",s.getInt("playerIdentity")).put("provenance",provenance)
        test.sendStatus(0,Bundle().apply { putString("stream","RECOVERY_EVENT $extra\n") })
    }
    private fun await(label:String,condition:()->Boolean) { val until=SystemClock.elapsedRealtime()+5000;while(SystemClock.elapsedRealtime()<until) { if(condition())return;SystemClock.sleep(40) };error("Timeout $label") }
}

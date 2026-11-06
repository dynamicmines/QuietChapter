package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import com.quietchapter.media.DemoCatalog
import com.quietchapter.narrative.*
import com.quietchapter.recovery.RecoveryMode
import org.json.JSONArray
import org.json.JSONObject

/** Actual service/player and cached AI-derived map; all navigation is explicitly SIMULATED. */
class NarrativeSuite(private val test: Instrumentation) {
    private var generation=0L
    private var scenario = ""
    private lateinit var map:NarrativeTimeline
    fun run(mode:String) {
        try {
            scenario = mode
            val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ServiceTestClient(test).use { c ->
                c.prepare();await("source-bound prepared timeline") { c.snapshot().getBoolean("narrativeReady") }
                map=NarrativeTimelineCodec.decode(test.targetContext.assets.open("demo-narrative-map.json").bufferedReader().use { it.readText() }) ?: error("Packaged map invalid")
                if (mode !in listOf("codec","restore_prepare","restore_verify","benchmark")) {
                    await("parked mode options") { var enabled=false;c.main { enabled=activity.findViewById<android.view.View>(R.id.options_toggle).isEnabled };enabled }
                    c.main { if (!activity.findViewById<android.view.View>(R.id.recovery_mode).isShown) activity.findViewById<android.view.View>(R.id.options_toggle).performClick() }
                }
                val old=c.snapshot().getString("recoveryMode") ?: "EXACT"
                try {
                    when(mode) {
                        "comparison" -> comparison(c)
                        "cluster" -> cluster(c)
                        "predictive" -> predictive(c)
                        "guards" -> guards(c)
                        "repeated" -> repeated(c)
                        "codec" -> codec(c)
                        "benchmark" -> benchmark(c)
                        "restore_prepare" -> { c.pause();c.command(QuietCommands.STOP_SIMULATION);setMode(c,"NARRATIVE");c.select(DemoCatalog.CHAPTER_TWO_ID);c.seek(7600);c.pause();SystemClock.sleep(1300);event(c,"narrative_restore_prepared") }
                        "restore_verify" -> {
                            val saved=c.snapshot();check(saved.getString("recoveryMode")=="NARRATIVE" && saved.getString("mediaId")==DemoCatalog.CHAPTER_TWO_ID)
                            check(saved.getLong("positionMs") in 7450..7750 && saved.getBoolean("narrativeReady") && saved.getBoolean("narrativeCacheHit"))
                            check(!saved.getBoolean("actualPlayWhenReady") && !saved.getBoolean("softPausePending") && !saved.getBoolean("recoveryPending"))
                            c.play();await("offline explicit restored Play") { c.snapshot().getBoolean("actualPlaying") };check(c.snapshot().getInt("recoverySeeks")==0)
                            event(c,"narrative_cache_restored_no_autoplay");c.pause();setMode(c,"EXACT")
                        }
                        else -> error("Unknown narrative scenario")
                    }
                } finally { c.pause();c.command(QuietCommands.STOP_SIMULATION);if (!mode.startsWith("restore_")) setMode(c,old) }
            }
            test.finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Narrative $mode PASS; actual service; ${if(mode in listOf("codec","benchmark") || mode.startsWith("restore_")) "NO_NAVIGATION" else "SIMULATED navigation"}; audio assessed separately\n") })
        } catch(failure:Throwable) { test.finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","Narrative $mode FAIL: ${failure.stackTraceToString()}\n") }) }
    }
    private fun comparison(c:ServiceTestClient) {
        for(mode in listOf(RecoveryMode.EXACT,RecoveryMode.FIXED_BACKTRACK,RecoveryMode.NARRATIVE)) {
            reset(c,mode,9000);awaitPosition(c,12200);send(c,0);await("mid-sentence pause") { c.snapshot().getString("state")=="AUTO_PAUSED" }
            val pause=c.snapshot().getLong("positionMs");check(pause in 12200..12600)
            event(c,"${mode.name}_paused",JSONObject().put("pauseMs",pause))
            val expected=when(mode) { RecoveryMode.EXACT->pause;RecoveryMode.FIXED_BACKTRACK->pause-3000;else->map.recovery(pause,"chapter:1",heardFromPositionMs=9000)?.positionMs ?: error("No eligible heard sentence") }
            clear(c,"${mode.name}_resume_clear")
            await("resume $mode") { c.snapshot().getBoolean("actualPlaying") }
            val resumed=c.snapshot();check(resumed.getLong("recoveryTargetMs")==expected) { "Wrong recovery target $resumed / $expected" }
            check(resumed.getLong("positionMs") in expected..expected+600)
            if(mode==RecoveryMode.NARRATIVE) check(resumed.getString("lastRecovery").orEmpty().startsWith("NARRATIVE_SENTENCE"))
            val atSentence=map.annotations.any { it.kind==NarrativeKind.SENTENCE && it.reliability==NarrativeReliability.VERIFIED && it.startMs==expected }
            event(c,"${mode.name}_resumed",JSONObject().put("pausedMs",pause).put("selectedTargetMs",expected).put("repeatedMs",pause-expected)
                .put("targetIsPreparedSentenceStart",atSentence).put("unheardContentSkipped",false).put("firstEmittedAudioBoundary","UNVERIFIED_UNTIL_CAPTURE_ALIGNMENT"))
            SystemClock.sleep(1000);c.pause()
        }
    }
    private fun cluster(c:ServiceTestClient) {
        reset(c,RecoveryMode.NARRATIVE,9000);awaitPosition(c,12200);send(c,0)
        await("cluster pause") { c.snapshot().getString("state")=="AUTO_PAUSED" };val before=c.snapshot();event(c,"cluster_first_pause")
        send(c,60);SystemClock.sleep(700);send(c,0);SystemClock.sleep(700)
        check(!c.snapshot().getBoolean("actualPlayWhenReady") && c.snapshot().getInt("actualStarts")==before.getInt("actualStarts"));event(c,"cluster_second_instruction_no_restart")
        clear(c,"cluster_clear");await("cluster resumes") { c.snapshot().getBoolean("actualPlaying") }
        check(c.snapshot().getInt("recoverySeeks")==before.getInt("recoverySeeks")+1)
        check(c.snapshot().getInt("actualStarts")==before.getInt("actualStarts")+1);event(c,"cluster_one_sentence_recovery");SystemClock.sleep(1000)
        reset(c,RecoveryMode.NARRATIVE,9000);awaitPosition(c,12200);send(c,0);await("manual override auto pause") { c.snapshot().getString("state")=="AUTO_PAUSED" }
        c.pause();val count=c.snapshot().getInt("actualStarts");clear(c,"manual_clear");check(c.snapshot().getString("state")=="USER_PAUSED" && !c.snapshot().getBoolean("actualPlayWhenReady") && c.snapshot().getInt("actualStarts")==count);event(c,"manual_pause_wins")
    }
    private fun predictive(c:ServiceTestClient) {
        reset(c,RecoveryMode.NARRATIVE,10700);send(c,7);await("soft pause pending") { c.snapshot().getBoolean("softPausePending") }
        val pending=c.snapshot();val target=pending.getLong("softPauseTargetMs");check(target==12795L);check(pending.getBoolean("actualPlaying"));event(c,"C_soft_pause_scheduled",JSONObject().put("plannedTargetMs",target))
        SystemClock.sleep(300);send(c,7);check(c.snapshot().getLong("softPauseDeadlineMs")==pending.getLong("softPauseDeadlineMs"))
        await("sentence-end pause") { c.snapshot().getString("state")=="AUTO_PAUSED" }
        val stopped=c.snapshot();check(stopped.getLong("positionMs") in target..target+80) { "Stopped outside corroborated boundary $stopped" }
        check(stopped.getBoolean("pausedAtSentenceEnd") && !stopped.getBoolean("actualPlayWhenReady"))
        event(c,"C_natural_boundary_paused",JSONObject().put("plannedTargetMs",target).put("stopErrorMs",stopped.getLong("positionMs")-target))
        val seeks=stopped.getInt("recoverySeeks");clear(c,"C_fresh_clear");await("continue after full sentence") { c.snapshot().getBoolean("actualPlaying") }
        check(c.snapshot().getInt("recoverySeeks")==seeks && c.snapshot().getString("lastRecovery").orEmpty().contains("completed sentence"))
        event(c,"C_exact_continuation_no_needless_rewind");SystemClock.sleep(1200)
    }
    private fun guards(c:ServiceTestClient) {
        reset(c,RecoveryMode.NARRATIVE,12000);awaitPosition(c,12200);send(c,0);await("new interruption after seek") { c.snapshot().getString("state")=="AUTO_PAUSED" };clear(c,"seek_floor_clear");await("seek-floor recovery") { c.snapshot().getBoolean("actualPlaying") }
        check(c.snapshot().getLong("recoveryTargetMs")>=12000);event(c,"seek_floor_never_replays_unheard_start")

        reset(c,RecoveryMode.NARRATIVE,10700);val stamp=SystemClock.elapsedRealtime();send(c,7,stamp);await("conflict pending") { c.snapshot().getBoolean("softPausePending") };send(c,60,stamp)
        check(!c.snapshot().getBoolean("softPausePending") && c.snapshot().getString("state")=="NORMAL");SystemClock.sleep(2500)
        check(c.snapshot().getBoolean("actualPlaying"));event(c,"contradictory_event_cancels_prediction")

        reset(c,RecoveryMode.NARRATIVE,10700);send(c,7);await("shorter ETA pending") { c.snapshot().getBoolean("softPausePending") };SystemClock.sleep(100);send(c,2)
        await("shorter ETA immediate fallback") { c.snapshot().getString("state")=="AUTO_PAUSED" };check(!c.snapshot().getBoolean("pausedAtSentenceEnd") && c.snapshot().getLong("positionMs")<12795);event(c,"tightened_eta_does_not_wait_past_event")

        reset(c,RecoveryMode.NARRATIVE,10700);send(c,7);await("manual pending") { c.snapshot().getBoolean("softPausePending") };c.pause();SystemClock.sleep(2500)
        check(c.snapshot().getString("state")=="USER_PAUSED" && !c.snapshot().getBoolean("actualPlayWhenReady") && !c.snapshot().getBoolean("softPausePending"));event(c,"manual_pause_cancels_soft_pause")

        reset(c,RecoveryMode.NARRATIVE,10700);send(c,7);await("seek pending") { c.snapshot().getBoolean("softPausePending") };c.seek(8000);SystemClock.sleep(2500)
        check(!c.snapshot().getBoolean("softPausePending") && c.snapshot().getBoolean("actualPlaying"));event(c,"manual_seek_cancels_soft_pause")

        reset(c,RecoveryMode.NARRATIVE,10700);send(c,7);await("focus pending") { c.snapshot().getBoolean("softPausePending") }
        val manager=test.targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setOnAudioFocusChangeListener { }.build()
        val now=SystemClock.elapsedRealtime();check(manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        try {
            await("focus cancels pending without sentence wait",1000) { c.snapshot().getString("state")=="INTERRUPTED" }
            check(!c.snapshot().getBoolean("softPausePending") && !c.snapshot().getBoolean("actualPlaying"))
            val preemption=SystemClock.elapsedRealtime()-now
            val seeks=c.snapshot().getInt("recoverySeeks")
            repeat(2) { c.play();SystemClock.sleep(120);check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getInt("recoverySeeks")==seeks) }
            c.pause();event(c,"system_focus_preempts_sentence_wait_blocked_play_does_not_rewind",JSONObject().put("observedDelayMs",preemption))
        }
        finally { manager.abandonAudioFocusRequest(focus) }
        SystemClock.sleep(400);check(!c.snapshot().getBoolean("actualPlayWhenReady"))
    }
    private fun benchmark(c:ServiceTestClient) {
        c.pause();c.command(QuietCommands.STOP_SIMULATION);setMode(c,"NARRATIVE");c.select(DemoCatalog.BOOK_ID);c.seek(9000);c.play()
        await("offline prepared playback") { c.snapshot().getBoolean("actualPlaying") }
        repeat(1000) { map.recovery(12200,"chapter:1",heardFromPositionMs=9000);map.softPause(10700,13700,"chapter:1") }
        val wall=SystemClock.elapsedRealtimeNanos();val cpu=android.os.Debug.threadCpuTimeNanos()
        repeat(100000) { check(map.recovery(12200,"chapter:1",heardFromPositionMs=9000)?.positionMs==11450L);check(map.softPause(10700,13700,"chapter:1")?.positionMs==12795L) }
        event(c,"stationary_lookup_benchmark_no_inference",JSONObject().put("queries",200000).put("wallMs",(SystemClock.elapsedRealtimeNanos()-wall)/1_000_000.0).put("threadCpuMs",(android.os.Debug.threadCpuTimeNanos()-cpu)/1_000_000.0).put("processPssKiB",android.os.Debug.getPss()))
        SystemClock.sleep(10000)
    }
    private fun repeated(c:ServiceTestClient) {
        val manager=test.targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        reset(c,RecoveryMode.NARRATIVE,9000);awaitPosition(c,12200)
        val initialSeeks=c.snapshot().getInt("recoverySeeks")
        repeat(3) { attempt ->
            check(manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            try { await("genuine repeated focus interruption") { c.snapshot().getString("state")=="INTERRUPTED" };check(!c.snapshot().getBoolean("actualPlaying")) }
            finally { manager.abandonAudioFocusRequest(focus) }
            SystemClock.sleep(80);check(!c.snapshot().getBoolean("actualPlayWhenReady"));c.play()
            await("explicit repeated recovery") { c.snapshot().getBoolean("actualPlaying") }
            val s=c.snapshot();check(s.getLong("recoveryTargetMs")>=11450)
            check(s.getInt("recoverySeeks")==initialSeeks+1) { "Repeated short interruption rewound again: $s" }
            event(c,"repeated_focus_recovery_${attempt+1}_no_anchor_staircase")
        }
    }
    private fun codec(c:ServiceTestClient) {
        val original=test.targetContext.assets.open("demo-narrative-map.json").bufferedReader().use { it.readText() }
        check(NarrativeTimelineCodec.decode("invalid") == null)
        val missing=JSONObject(original).put("annotations",JSONArray());val basic=NarrativeTimelineCodec.decode(missing.toString())!!;check(!basic.hasVerifiedSentences && basic.recovery(12200,"chapter:1")==null)
        val invalid=JSONObject(original);invalid.getJSONArray("annotations").getJSONObject(0).put("endMs",-1);check(NarrativeTimelineCodec.decode(invalid.toString())==null)
        val acoustic=JSONObject(original);val a=acoustic.getJSONArray("annotations");for(i in 0 until a.length())if(a.getJSONObject(i).getString("kind")=="SENTENCE")a.getJSONObject(i).put("source","ACOUSTIC_VAD")
        check(NarrativeTimelineCodec.decode(acoustic.toString())==null)
        val low=JSONObject(original);val la=low.getJSONArray("annotations");for(i in 0 until la.length())if(la.getJSONObject(i).getString("kind")=="SENTENCE")la.getJSONObject(i).put("reliability","ADVISORY")
        check(!NarrativeTimelineCodec.decode(low.toString())!!.hasVerifiedSentences)
        check(NarrativeTimelineCodec.decode(" ".repeat(2_000_001))==null)
        val unknown=JSONObject(original).put("language","und");check(!NarrativeTimelineCodec.decode(unknown.toString())!!.hasVerifiedSentences)
        event(c,"codec_missing_invalid_acoustic_low_confidence_oversize_unknown_language_rejected")
    }
    private fun reset(c:ServiceTestClient,mode:RecoveryMode,start:Long) {
        c.pause();c.command(QuietCommands.STOP_SIMULATION);setMode(c,mode.name);c.select(DemoCatalog.BOOK_ID);c.seek(start)
        generation=c.snapshot().getLong("simulationGeneration")+1;send(c,60);c.play();await("actual playback") { c.snapshot().getBoolean("actualPlaying") }
    }
    private fun setMode(c:ServiceTestClient,mode:String)=c.command(QuietCommands.RECOVERY_MODE,Bundle().apply { putString("mode",mode) })
    private fun send(c:ServiceTestClient,eta:Int,stamp:Long=SystemClock.elapsedRealtime())=c.command(QuietCommands.SIMULATED_EVENT,Bundle().apply { putInt("eta",eta);putLong("generation",generation);putLong("sourceElapsedMs",stamp) })
    private fun clear(c:ServiceTestClient,name:String) { SystemClock.sleep(30);send(c,60);SystemClock.sleep(2100);event(c,name);send(c,60) }
    private fun awaitPosition(c:ServiceTestClient,position:Long)=await("position $position",8000) { c.snapshot().getLong("positionMs")>=position }
    private fun event(c:ServiceTestClient,name:String,extra:JSONObject=JSONObject()) {
        val s=c.snapshot();extra.put("stage",name).put("deviceNs",SystemClock.elapsedRealtimeNanos()).put("positionMs",s.getLong("positionMs")).put("state",s.getString("state"))
            .put("actualPlaying",s.getBoolean("actualPlaying")).put("playWhenReady",s.getBoolean("actualPlayWhenReady")).put("mode",s.getString("recoveryMode"))
            .put("strategy",s.getString("lastRecovery")).put("targetMs",s.getLong("recoveryTargetMs")).put("anchor",s.getString("narrativeAnchor")).put("seeks",s.getInt("recoverySeeks"))
            .put("starts",s.getInt("actualStarts")).put("playerIdentity",s.getInt("playerIdentity")).put("narrativeReady",s.getBoolean("narrativeReady"))
            .put("cacheHit",s.getBoolean("narrativeCacheHit"))
            .put("provenance",if(scenario in listOf("codec","benchmark") || scenario.startsWith("restore_")) "ACTUAL_METADATA_SERVICE_NO_NAVIGATION" else "SIMULATED_NAVIGATION_REAL_ENGINE_AND_PLAYER")
        test.sendStatus(0,Bundle().apply { putString("stream","NARRATIVE_EVENT $extra\n") })
    }
    private fun await(label:String,timeout:Long=5000,condition:()->Boolean) { val end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end) { if(condition())return;SystemClock.sleep(20) };error("Timeout $label") }
}

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
import com.quietchapter.recovery.*
import org.json.JSONObject

/** Real player/output acceptance. No listener preference or native navigation is manufactured. */
class NarrativeAcceptanceSuite(private val test: Instrumentation) {
    private var generation=0L
    private lateinit var map: NarrativeTimeline
    private data class Case(val id:Int,val chapterTwo:Boolean,val start:Long,val pause:Long,val accepted:Boolean)
    fun run(name:String) {
        try {
            val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ServiceTestClient(test).use { c ->
                c.prepare();await("hash-bound timeline") { c.snapshot().getBoolean("narrativeReady") }
                map=NarrativeTimelineCodec.decode(test.targetContext.assets.open("demo-narrative-map.json").bufferedReader().use { it.readText() })!!
                val old=c.snapshot().getString("recoveryMode") ?: "EXACT"
                try {
                    await("parked controls") { var ok=false;c.main { ok=activity.findViewById<android.view.View>(R.id.options_toggle).isEnabled };ok }
                    c.main { if(!activity.findViewById<android.view.View>(R.id.recovery_mode).isShown)activity.findViewById<android.view.View>(R.id.options_toggle).performClick() }
                    if(name=="focus") focus(c) else {
                        val fixture=when(name) {
                            "accepted2"->Case(2,false,3500,7400,true)
                            "accepted4"->Case(4,false,9000,12200,true)
                            "accepted13"->Case(13,true,16000,19500,true)
                            "rejected3"->Case(3,false,8500,10800,false)
                            "rejected8"->Case(8,false,19500,23500,false)
                            "rejected10"->Case(10,true,5000,9000,false)
                            else->error("Unknown acceptance case")
                        }
                        comparison(c,fixture)
                    }
                } finally { c.pause();c.command(QuietCommands.STOP_SIMULATION);mode(c,old) }
            }
            test.finish(Activity.RESULT_OK,Bundle().apply {putString("stream","Narrative acceptance $name PASS; actual player; ${if(name=="focus")"ANDROID_FOCUS_TEST_NO_NAVIGATION" else "SIMULATED_NAVIGATION"}; listening acceptance UNVERIFIED\n")})
        } catch(e:Throwable) {test.finish(Activity.RESULT_CANCELED,Bundle().apply {putString("stream","Narrative acceptance $name FAIL: ${e.stackTraceToString()}\n")})}
    }
    private fun comparison(c:ServiceTestClient,f:Case) {
        val candidate=map.annotations.single {it.id=="sentence-${f.id}"}
        check((candidate.reliability==NarrativeReliability.VERIFIED)==f.accepted)
        val offset=if(f.chapterTwo)24000L else 0L
        val chapter=if(f.chapterTwo)"chapter:2" else "chapter:1"
        for(m in listOf(RecoveryMode.EXACT,RecoveryMode.FIXED_BACKTRACK,RecoveryMode.NARRATIVE)) {
            c.pause();c.command(QuietCommands.STOP_SIMULATION);mode(c,m.name)
            c.select(if(f.chapterTwo)DemoCatalog.CHAPTER_TWO_ID else DemoCatalog.BOOK_ID);c.seek(f.start)
            generation=c.snapshot().getLong("simulationGeneration")+1;send(c,60);c.play();await("actual start") {c.snapshot().getBoolean("actualPlaying")}
            event(c,"play_started",f.id,m.name,JSONObject().put("heardFloorMs",f.start).put("assetOffsetMs",offset))
            await("target position",8000) {c.snapshot().getLong("positionMs")>=f.pause}
            val issued=SystemClock.elapsedRealtimeNanos();send(c,0);await("automatic pause") {c.snapshot().getString("state")=="AUTO_PAUSED"}
            SystemClock.sleep(90) // Observe final paused clock, not an advancing estimate.
            val pause=c.snapshot().getLong("positionMs");check(pause in f.pause..f.pause+450)
            event(c,"paused",f.id,m.name,JSONObject().put("commandDeviceNs",issued))
            val anchor=map.recovery(pause+offset,chapter,heardFromPositionMs=f.start+offset)
            if(f.accepted)check(anchor?.annotationId==candidate.id) else check(anchor?.annotationId!=candidate.id)
            val expected=when(m) {
                RecoveryMode.EXACT->pause
                RecoveryMode.FIXED_BACKTRACK->(pause-3000).coerceAtLeast(0)
                else->anchor?.positionMs?.minus(offset) ?: -1
            }
            send(c,60);SystemClock.sleep(2100);val clearIssued=SystemClock.elapsedRealtimeNanos()
            event(c,"clear_will_resume",f.id,m.name,JSONObject().put("commandDeviceNs",clearIssued));send(c,60)
            await("actual resumed output") {c.snapshot().getBoolean("actualPlaying")}
            val resumed=c.snapshot();val target=resumed.getLong("recoveryTargetMs")
            if(expected>=0)check(target==expected) {"Unexpected target $target expected $expected: $resumed"}
            check(target<=pause && target>=0)
            if(m==RecoveryMode.NARRATIVE) {
                check(target>=f.start)
                if(f.accepted)check(resumed.getString("lastRecovery").orEmpty().startsWith("NARRATIVE_SENTENCE"))
                else check(!resumed.getString("lastRecovery").orEmpty().startsWith("NARRATIVE_SENTENCE"))
            }
            event(c,"resumed",f.id,m.name,JSONObject().put("pausedMs",pause).put("selectedTargetMs",target).put("assetOffsetMs",offset)
                .put("repeatMs",pause-target).put("candidateAccepted",f.accepted).put("candidateStartAssetMs",candidate.startMs)
                .put("annotationId",anchor?.annotationId ?: "NO_ELIGIBLE_SENTENCE").put("listeningAcceptance","UNVERIFIED"))
            SystemClock.sleep(850);c.pause()
        }
    }
    private fun focus(c:ServiceTestClient) {
        val manager=test.targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        for((name,gain,usage) in listOf(Triple("navigation_may_duck",AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE),
            Triple("transient_media",AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,AudioAttributes.USAGE_MEDIA),Triple("permanent_media",AudioManager.AUDIOFOCUS_GAIN,AudioAttributes.USAGE_MEDIA))) {
            c.pause();c.command(QuietCommands.STOP_SIMULATION);mode(c,"NARRATIVE");c.select(DemoCatalog.BOOK_ID);c.seek(9000);c.play()
            await("focus baseline playing") {c.snapshot().getBoolean("actualPlaying")};SystemClock.sleep(1100)
            event(c,"focus_baseline",0,name,JSONObject().put("provenance","ANDROID_PLATFORM_TEST_CONTROLLER_NO_NAVIGATION_DATA"))
            val request=AudioFocusRequest.Builder(gain).setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build()).setOnAudioFocusChangeListener { }.build()
            val at=SystemClock.elapsedRealtimeNanos();val granted=manager.requestAudioFocus(request)
            try {
                check(granted==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                SystemClock.sleep(1100);event(c,"focus_held",0,name,JSONObject().put("focusGain",gain).put("focusUsage",usage).put("commandDeviceNs",at).put("requestResult",granted).put("provenance","ANDROID_PLATFORM_TEST_CONTROLLER_NO_NAVIGATION_DATA"))
                val starts=c.snapshot().getInt("actualStarts");c.pause();manager.abandonAudioFocusRequest(request);SystemClock.sleep(500)
                check(!c.snapshot().getBoolean("actualPlayWhenReady") && c.snapshot().getInt("actualStarts")==starts)
                c.seek(10500);check(!c.snapshot().getBoolean("actualPlayWhenReady"));event(c,"manual_pause_seek_survives_focus_return",0,name)
                c.play();await("explicit Play after focus") {c.snapshot().getBoolean("actualPlaying")};SystemClock.sleep(500);c.pause()
            } finally {manager.abandonAudioFocusRequest(request);c.pause()}
        }
    }
    private fun mode(c:ServiceTestClient,m:String)=c.command(QuietCommands.RECOVERY_MODE,Bundle().apply {putString("mode",m)})
    private fun send(c:ServiceTestClient,eta:Int)=c.command(QuietCommands.SIMULATED_EVENT,Bundle().apply {putInt("eta",eta);putLong("generation",generation);putLong("sourceElapsedMs",SystemClock.elapsedRealtime())})
    private fun event(c:ServiceTestClient,stage:String,id:Int,method:String,extra:JSONObject=JSONObject()) {
        val s=c.snapshot();extra.put("stage",stage).put("candidate",id).put("method",method).put("deviceNs",SystemClock.elapsedRealtimeNanos())
            .put("positionMs",s.getLong("positionMs")).put("state",s.getString("state")).put("actualPlaying",s.getBoolean("actualPlaying"))
            .put("playWhenReady",s.getBoolean("actualPlayWhenReady")).put("playerState",s.getInt("actualPlaybackState"))
            .put("recoveryTargetMs",s.getLong("recoveryTargetMs")).put("strategy",s.getString("lastRecovery")).put("seeks",s.getInt("recoverySeeks"))
            .put("starts",s.getInt("actualStarts")).put("playerIdentity",s.getInt("playerIdentity"))
        if(!extra.has("provenance"))extra.put("provenance",if(id==0)"ANDROID_PLATFORM_TEST_CONTROLLER_NO_NAVIGATION_DATA" else "SIMULATED_NAVIGATION_REAL_PLAYER")
        test.sendStatus(0,Bundle().apply {putString("stream","ACCEPTANCE_EVENT $extra\n")})
    }
    private fun await(label:String,timeout:Long=5000,condition:()->Boolean) {val end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition())return;SystemClock.sleep(20)};error("Timeout $label")}
}

package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import com.quietchapter.media.DemoCatalog
import org.json.JSONObject

/** Parked demonstration of existing policy. All navigation here is explicitly SIMULATED. */
class Phase11DemoSuite(private val test: Instrumentation) {
    private lateinit var activity: MainActivity
    private var generation = 0L
    fun run() {
        try {
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            ServiceTestClient(test).use { c ->
                try {
                    c.prepare()
                    c.main { activity.findViewById<Button>(R.id.demo_toggle)?.performClick() }
                    await("parked controls") { var enabled=false; c.main { enabled=activity.findViewById<Button>(R.id.play).isEnabled }; enabled }
                    reset(c)
                    click(c,R.id.play); await("initial playback") { c.snapshot().getBoolean("actualPlaying") }
                    click(c,R.id.simulate); event(c,"existing_fixture_started")
                    await("existing SIMULATED pause") { c.snapshot().getString("state")=="AUTO_PAUSED" }
                    event(c,"existing_fixture_auto_paused")
                    await("existing SIMULATED resume",12_000) { c.snapshot().getString("state")=="NORMAL" && c.snapshot().getBoolean("actualPlaying") }
                    event(c,"existing_fixture_clear_resume")
                    await("existing fixture route ended",5_000) { c.snapshot().getString("route").orEmpty().contains("active=false") }
                    check(c.snapshot().getBoolean("actualPlaying"))
                    event(c,"existing_fixture_completed")

                    reset(c); send(c,20); click(c,R.id.play)
                    await("two-window playback") { c.snapshot().getBoolean("actualPlaying") }; event(c,"two_windows_approach")
                    SystemClock.sleep(700); send(c,5)
                    await("first imminent window") { c.snapshot().getString("state")=="AUTO_PAUSED" }; event(c,"two_windows_first_pause")
                    SystemClock.sleep(700); send(c,60)
                    SystemClock.sleep(700); send(c,4)
                    check(c.snapshot().getString("state")=="AUTO_PAUSED" && !c.snapshot().getBoolean("actualPlayWhenReady"))
                    event(c,"two_windows_second_imminent_stays_paused")
                    SystemClock.sleep(700); send(c,60)
                    SystemClock.sleep(2_100); send(c,60)
                    await("grouped clear resume") { c.snapshot().getString("state")=="NORMAL" && c.snapshot().getBoolean("actualPlaying") }
                    event(c,"two_windows_clear_resume"); SystemClock.sleep(2_000)

                    reset(c); click(c,R.id.play); await("manual test playing") { c.snapshot().getBoolean("actualPlaying") }
                    click(c,R.id.simulate); event(c,"manual_fixture_started")
                    await("manual test automatic pause") { c.snapshot().getString("state")=="AUTO_PAUSED" }
                    click(c,R.id.pause); val starts=c.snapshot().getInt("actualStarts"); event(c,"manual_pause_overrides")
                    await("manual test clear input",12_000) { c.snapshot().getString("route").orEmpty().contains("ETA=60") && c.snapshot().getString("log").orEmpty().contains("ETA=60") }
                    // Wait past the original11s clear sample (route labels use 'maneuver ETA=60 s').
                    SystemClock.sleep(8_300)
                    val manual=c.snapshot()
                    check(manual.getString("state")=="USER_PAUSED" && !manual.getBoolean("actualPlayWhenReady") && manual.getInt("actualStarts")==starts)
                    event(c,"manual_clear_did_not_resume"); SystemClock.sleep(1_200)

                    reset(c); send(c,60); click(c,R.id.play); await("loss test playing") { c.snapshot().getBoolean("actualPlaying") }
                    send(c,5); await("loss test paused") { c.snapshot().getString("state")=="AUTO_PAUSED" }
                    event(c,"source_feed_stops")
                    await("missing SIMULATED data expires",6_500) { c.snapshot().getString("navigationHealth")=="EXPIRED" }
                    check(c.snapshot().getString("state")=="INTERRUPTED" && !c.snapshot().getBoolean("actualPlayWhenReady"))
                    event(c,"source_expired_no_resume")
                    send(c,60); SystemClock.sleep(2_100); send(c,60)
                    check(c.snapshot().getString("state")=="INTERRUPTED" && !c.snapshot().getBoolean("actualPlayWhenReady"))
                    event(c,"source_recovered_needs_explicit_play")
                    click(c,R.id.play); await("explicit source recovery") { c.snapshot().getBoolean("actualPlaying") }
                    event(c,"explicit_recovery_play"); SystemClock.sleep(1_500)
                    click(c,R.id.pause); event(c,"demo_completed_paused")
                } finally { c.pause(); c.command(QuietCommands.STOP_SIMULATION) }
            }
            test.finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Phase11 SIMULATED demonstration PASS; digital/physical audio assessed separately\n") })
        } catch (failure:Throwable) {
            test.finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","Phase11 SIMULATED demonstration FAIL: ${failure.stackTraceToString()}\n") })
        }
    }
    private fun reset(c:ServiceTestClient) {
        c.pause(); c.command(QuietCommands.STOP_SIMULATION); c.select(DemoCatalog.BOOK_ID); c.seek(0)
        generation=c.snapshot().getLong("simulationGeneration")+1
    }
    private fun send(c:ServiceTestClient,eta:Int) = c.command(QuietCommands.SIMULATED_EVENT,Bundle().apply {
        putInt("eta",eta); putLong("generation",generation); putLong("sourceElapsedMs",SystemClock.elapsedRealtime())
    })
    private fun click(c:ServiceTestClient,id:Int) = c.main {
        val button=activity.findViewById<Button>(id);check(button.isEnabled) { "Parked control disabled" };check(button.performClick())
    }
    private fun event(c:ServiceTestClient,name:String) {
        val s=c.snapshot(); val data=JSONObject().put("stage",name).put("provenance","SIMULATED_NAVIGATION_REAL_PLAYER")
            .put("deviceNs",SystemClock.elapsedRealtimeNanos()).put("state",s.getString("state"))
            .put("health",s.getString("navigationHealth")).put("playing",s.getBoolean("actualPlaying"))
            .put("playWhenReady",s.getBoolean("actualPlayWhenReady")).put("positionMs",s.getLong("positionMs"))
            .put("starts",s.getInt("actualStarts")).put("playerIdentity",s.getInt("playerIdentity")).put("route",s.getString("route"))
        test.sendStatus(0,Bundle().apply { putString("stream","DEMO_EVENT $data\n") })
    }
    private fun await(label:String,timeout:Long=5_000,check:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<until) { if(check())return;SystemClock.sleep(50) }
        error("Timed out: $label")
    }
}

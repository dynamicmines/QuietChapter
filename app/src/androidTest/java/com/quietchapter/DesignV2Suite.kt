package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual parked pages and control geometry; deterministic restriction preview is labelled TEST_ONLY. */
class DesignV2Suite(private val test:Instrumentation) {
    fun run(mode:String){
        try {
            val a=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            ServiceTestClient(test).use { c ->
                c.prepare();c.pause();c.command(QuietCommands.STOP_SIMULATION)
                c.main { a.findViewById<Button>(R.id.nav_player).performClick() }
                await { var enabled=false;c.main{enabled=a.findViewById<View>(R.id.play).isEnabled};enabled }
                if(mode=="controls") {
                    c.select(com.quietchapter.media.DemoCatalog.CHAPTER_ONE_ID);c.seek(3000)
                    c.main { a.findViewById<Button>(R.id.play).performClick() }
                    await { c.snapshot().getBoolean("actualPlaying") }
                    SystemClock.sleep(350)
                    c.main { check(a.findViewById<View>(R.id.pause).isActivated);check(!a.findViewById<View>(R.id.play).isActivated) }
                    snap(a,"playing-action")
                    c.main { a.findViewById<Button>(R.id.pause).performClick() }
                    await { !c.snapshot().getBoolean("actualPlaying") }
                    val starts=c.snapshot().getInt("actualStarts")
                    c.main { a.findViewById<Button>(R.id.next_chapter).performClick() }
                    await { c.snapshot().getString("mediaId")==com.quietchapter.media.DemoCatalog.CHAPTER_TWO_ID }
                    check(!c.snapshot().getBoolean("actualPlayWhenReady")&&c.snapshot().getInt("actualStarts")==starts)
                    c.main { a.findViewById<Button>(R.id.previous_chapter).performClick() }
                    await { c.snapshot().getString("mediaId")==com.quietchapter.media.DemoCatalog.CHAPTER_ONE_ID }
                    check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                    // The controller revision can precede the Activity's250ms presentation tick.
                    // Touch the seekbar only once its displayed chapter/duration actually match.
                    val seconds = c.snapshot().getLong("durationMs") / 1000
                    val expectedRemaining = a.getString(R.string.remaining_time, "%d:%02d".format(seconds / 60, seconds % 60))
                    await {
                        var ready = false
                        c.main { ready = a.findViewById<TextView>(R.id.playback_chapter).text == a.getString(R.string.now_chapter_one) && a.findViewById<TextView>(R.id.playback_remaining).text == expectedRemaining }
                        ready
                    }
                    c.main {
                        val bar=a.findViewById<android.widget.SeekBar>(R.id.seek)
                        val x=bar.paddingLeft+(bar.width-bar.paddingLeft-bar.paddingRight)*.5f
                        val now=SystemClock.uptimeMillis()
                        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
                            val event=android.view.MotionEvent.obtain(now,now,action,x,bar.height/2f,0);bar.dispatchTouchEvent(event);event.recycle()
                        }
                    }
                    await { c.snapshot().getLong("positionMs") in 11000L..13000L }
                    check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                    c.main { a.findViewById<Button>(R.id.nav_home).performClick();a.findViewById<Button>(R.id.continue_listening).performClick() }
                    await { c.snapshot().getBoolean("actualPlaying") }
                    SystemClock.sleep(300);snap(a,"continued-playing")
                    c.main { a.findViewById<Button>(R.id.pause).performClick() }
                    await { c.snapshot().getString("state")=="USER_PAUSED" }
                    c.main { a.findViewById<Button>(R.id.play).performClick() }
                    await { c.snapshot().getBoolean("actualPlaying") };c.command(QuietCommands.SIMULATE)
                    await { c.snapshot().getString("state")=="AUTO_PAUSED" }
                    await { var shown=false;c.main{shown=a.findViewById<View>(R.id.keep_paused).isShown};shown }
                    c.main { check(a.findViewById<View>(R.id.play).isShown);check(!a.findViewById<View>(R.id.pause).isShown);a.findViewById<Button>(R.id.keep_paused).performClick() }
                    await { c.snapshot().getString("state")=="USER_PAUSED" }
                    val manualStarts=c.snapshot().getInt("actualStarts");SystemClock.sleep(12500)
                    check(!c.snapshot().getBoolean("actualPlaying")&&c.snapshot().getInt("actualStarts")==manualStarts)
                    emit("PASS one visible primary, chapter/seek/Home controls; actual Keep paused defeats simulated route-clear resume")
                } else if(mode=="day" || mode=="night") {
                    val day=mode=="day"
                    c.main { a.findViewById<Button>(R.id.nav_more).performClick() }
                    SystemClock.sleep(150)
                    c.main {
                        val b=a.findViewById<Button>(R.id.appearance);check(b.isShown&&b.isEnabled)
                        if(a.getSharedPreferences("presentation",0).getBoolean("day",false)!=day)b.performClick()
                    }
                    SystemClock.sleep(400)
                    check(a.getSharedPreferences("presentation",0).getBoolean("day",false)==day)
                    emit("PASS actual parked appearance action; system theme unchanged")
                } else if(mode=="restricted") {
                    val before=c.snapshot().getInt("actualStarts")
                    c.main{
                        val preview=ParkedPlayerView(a)
                        preview.present(false,"TEST ONLY · Restricted UI. Use system media controls.",false,false,false)
                        preview.parkedControls.forEach{it.isEnabled=false}
                        a.setContentView(preview.root)
                    }
                    SystemClock.sleep(300)
                    c.main{check(!a.findViewById<View>(R.id.play).isShown);check(a.findViewById<View>(R.id.restricted_surface).isShown)}
                    snap(a,"restricted-test-only")
                    check(c.snapshot().getInt("actualStarts")==before && !c.snapshot().getBoolean("actualPlaying"))
                    emit("PASS TEST_ONLY restriction-rendering fail-closed; no vehicle-state transition claim")
                } else {
                    check(mode=="pages" || mode=="transport")
                    c.main{
                        val density=a.resources.displayMetrics.density
                        val bounds=Rect();a.findViewById<View>(android.R.id.content).getGlobalVisibleRect(bounds)
                        val rows=JSONArray()
                        for((id,min) in listOf((if(a.findViewById<View>(R.id.pause).isShown)R.id.pause else R.id.play) to 88,R.id.rewind to 64,R.id.seek to 64,R.id.previous_chapter to 64,R.id.next_chapter to 64)){
                            val v=a.findViewById<View>(id);val rect=Rect();check(v.isShown&&v.getGlobalVisibleRect(rect));check(bounds.contains(rect)&&rect.height()==v.height&&rect.width()==v.width){"Clipped transport $id $rect"};check(v.height/density>=min-.1f)
                            if(v is TextView)checkText(v)
                            rows.put(JSONObject().put("id",a.resources.getResourceEntryName(id)).put("widthDp",v.width/density).put("heightDp",v.height/density).put("bounds",rect.toShortString()))
                        }
                        File(a.cacheDir,"design-v2-metrics.json").writeText(JSONObject().put("result","PASS").put("viewport",bounds.toShortString()).put("density",density).put("fontScale",a.resources.configuration.fontScale).put("widthDp",a.resources.configuration.screenWidthDp).put("heightDp",a.resources.configuration.screenHeightDp).put("controls",rows).toString(2))
                        val fonts=JSONArray()
                        for(id in listOf(R.id.nav_home,R.id.nav_player,R.id.nav_library,R.id.nav_more,R.id.playback_elapsed,R.id.playback_remaining,R.id.playback_chapter,(if(a.findViewById<View>(R.id.pause).isShown)R.id.pause else R.id.play),R.id.previous_chapter,R.id.next_chapter)) {
                            val v=a.findViewById<TextView>(id);val text=v.text.toString().trim();val glyph=Rect();v.paint.getTextBounds(text,0,text.length,glyph)
                            if(text.isNotEmpty())check(glyph.height()>=18){"Small essential glyph $id $text $glyph"}
                            fonts.put(JSONObject().put("id",a.resources.getResourceEntryName(id)).put("text",text).put("sizeSp",v.textSize/a.resources.displayMetrics.scaledDensity).put("sizePx",v.textSize).put("glyphHeightPx",glyph.height()).put("family",QuietTypography.family(text)))
                        }
                        File(a.cacheDir,"silver-ui-metrics.json").writeText(JSONObject().put("result","PASS").put("rootBounds",bounds.toShortString()).put("fonts",fonts).put("controls",rows).put("physicalReadability","UNVERIFIED; virtual emulator density only").toString(2))
                    }
                    snap(a,"player");emit("PASS actual window transport88/64dp; fully visible, no clipped control text")
                    if(mode=="pages"){
                        for((key,nav) in listOf("home" to R.id.nav_home,"library" to R.id.nav_library,"chapters" to R.id.library_book,"player-return" to R.id.nav_player,"narrative" to R.id.open_narrative,"more" to R.id.nav_more,"road" to R.id.more_road,"more-again" to R.id.nav_more,"settings" to R.id.nav_more)){
                            c.main { val button=a.findViewById<Button>(nav);check(button.isShown&&button.isEnabled);button.performClick() };SystemClock.sleep(180)
                            c.main{
                                walk(a.findViewById(android.R.id.content))
                                val rowId=when(key){"library"->R.id.library_book;"chapters"->R.id.chapter_one;"settings"->R.id.appearance;else->0}
                                if(rowId!=0){val viewport=Rect();a.findViewById<View>(android.R.id.content).getGlobalVisibleRect(viewport);val row=a.findViewById<View>(rowId);check(row.width>=viewport.width()*.6f){"Content-sized semantic row $key ${row.width} in $viewport"}}
                            };snap(a,key)
                        }
                        emit("PASS actual Home/Library/Chapters/Narrative/Road/Preferences navigation and text geometry")
                    }
                }
                c.pause()
            }
            test.finish(Activity.RESULT_OK,Bundle().apply{putString("stream","DesignV2 $mode PASS; actual parked app; native route BLOCKED\n")})
        }catch(e:Throwable){test.finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream","DesignV2 $mode FAIL ${e.stackTraceToString()}\n")})}
    }
    private fun checkText(v:TextView){val l=v.layout?:return;check(l.getLineBottom(l.lineCount-1)<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom){"Clipped text ${v.text}"};for(i in 0 until l.lineCount)check(l.getLineWidth(i)<=l.width+.5f){"Overflow ${v.text}"}}
    private fun walk(v:View){if(!v.isShown)return;if(v is TextView)checkText(v);if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))}
    private fun snap(a:Activity,name:String){val bmp=test.uiAutomation.takeScreenshot()?:error("No screenshot");File(a.cacheDir,"design-v2-$name.png").outputStream().use{check(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};bmp.recycle()}
    private fun emit(s:String){test.sendStatus(0,Bundle().apply{putString("stream",s+"\n")})}
    private fun await(fn:()->Boolean){val end=SystemClock.elapsedRealtime()+7000;while(SystemClock.elapsedRealtime()<end){if(fn())return;SystemClock.sleep(30)};error("Parked controls unavailable")}
}

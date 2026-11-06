package com.quietchapter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Parked application UI. The service, native media host and all playback policy remain independent. */
class ParkedPlayerView(private val context: Context) {
    private val ink = color(R.attr.qc_ink)
    private val muted = color(R.attr.qc_muted)
    private val accent = color(R.attr.qc_accent)
    private val controls = mutableListOf<View>()
    val parkedControls: List<View> get() = controls
    private val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val width = context.resources.configuration.screenWidthDp
    private val height = context.resources.configuration.screenHeightDp
    private val wide = width >= 1000
    private val narrow = width < 600
    private val pages = linkedMapOf<String, View>()
    private val navigation = mutableMapOf<String, Button>()
    private var page = "player"
    val root: View
    val status: TextView
    val route: TextView
    val log: TextView
    val recoveryMode: Button
    val contextMode: TextView
    val recoveryDetail: TextView
    val appearance: Button
    val importAudio: Button
    val localAudio: Button
    val title: TextView
    val author: TextView
    val artwork: ImageView
    val prepare: Button
    val play: Button
    val pause: Button
    val rewind: Button
    val previous: Button
    val next: Button
    val prepareShortcut: Button
    val continueListening: Button
    val seek: SeekBar
    val simulate: Button
    val stopSimulation: Button
    val chapterOne: Button
    val chapterTwo: Button
    val chapter: TextView
    val elapsed: TextView
    val remaining: TextView
    val playback: TextView
    val health: TextView
    val recovery: TextView
    val parkedNotice: TextView
    val keepPaused: Button
    private val homeTitle: TextView
    private val homeAuthor: TextView
    private val homeChapter: TextView
    private val homeArt: ImageView
    private val narrativeReadiness: TextView
    private val narrativeCoverage: TextView
    private val roadAvailability: TextView
    private val screenHeading: TextView
    private val scroll: ScrollView
    private val body: LinearLayout
    private val pageHost: FrameLayout
    private val nav: LinearLayout
    private val restricted: LinearLayout
    private val restrictedMessage: TextView
    private val diagnosticContent: LinearLayout
    private val stage: LinearLayout
    private val footer: LinearLayout
    private var homeActionPlaying: Boolean? = null

    init {
        val frame = column().apply { setBackgroundColor(color(R.attr.qc_background)) }
        root = frame
        val header = column().apply { setPadding(dp(32), dp(12), dp(32), dp(12)) }
        val brand = row()
        brand.addView(text(R.string.app_name, 26, 600), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(brand, LinearLayout.LayoutParams(-1, dp(64)))
        nav = LinearLayout(context).apply { orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        for ((key, label, id) in listOf(Triple("home", R.string.nav_home, R.id.nav_home), Triple("player", R.string.nav_listen, R.id.nav_player), Triple("library", R.string.nav_library, R.id.nav_library), Triple("settings", R.string.screen_settings, R.id.nav_more))) {
            val b = button(label, id).apply {
                textSize = 24f; minimumHeight = dp(64); background = TabBackground()
                setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { showPage(key) }
            }
            if (wide || !narrow) {
                val image=context.getDrawable(when(key){"home"->R.drawable.qc_home;"library"->R.drawable.qc_library;"settings"->R.drawable.qc_settings;else->R.drawable.qc_play})!!.mutate().apply{
                    setBounds(0,0,dp(28),dp(28));setTintList(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_selected),intArrayOf()),intArrayOf(muted,accent,ink)))
                }
                b.setCompoundDrawablesRelative(image,null,null,null);b.compoundDrawablePadding=dp(12)
            }
            if (wide) {
                b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                nav.addView(b, LinearLayout.LayoutParams(-1, dp(72)).apply { if (nav.childCount > 0) topMargin = dp(8) })
            } else if (key == "settings") {
                b.setCompoundDrawablesRelative(null,null,null,null)
                val icon=context.getDrawable(R.drawable.qc_settings)!!.mutate().apply{setTint(ink);setBounds(0,0,dp(32),dp(32))}
                val caption=android.text.SpannableString(" ");caption.setSpan(android.text.style.ImageSpan(icon),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);b.text=caption
                b.contentDescription = context.getString(R.string.screen_settings)
                brand.addView(b, LinearLayout.LayoutParams(dp(64), dp(64)))
            } else {
                val itemWidth = minOf(200, (width - 88) / 3)
                nav.addView(b, LinearLayout.LayoutParams(dp(itemWidth), dp(64)).apply { if (nav.childCount > 0) marginStart = dp(12) })
            }
            navigation[key] = b
        }
        if (!wide) header.addView(nav, params(8))
        frame.addView(header)
        stage = row().apply { gravity = Gravity.TOP }
        if (wide) { nav.setPadding(dp(24), dp(32), dp(8), 0); stage.addView(nav, LinearLayout.LayoutParams(dp(232), -1)) }
        body = column().apply { setPadding(dp(if (narrow) 24 else 32), dp(24), dp(32), dp(24)) }
        screenHeading = text(R.string.screen_player, 34, 600).apply { id = R.id.screen_heading; heading(this) }
        body.addView(screenHeading)
        pageHost = FrameLayout(context); body.addView(pageHost, params(24))
        scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false; addView(body) }
        stage.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f)); frame.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // Artwork, book identity and real transport form one composition; no distant bottom dashboard.
        val player = LinearLayout(context).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val artSize = if (wide) minOf(((width - 296) * .44f).toInt(), (height * .62f).toInt()) else if (narrow) 160 else 288
        artwork = cover(artSize).apply { id = R.id.book_artwork }
        player.addView(artwork, LinearLayout.LayoutParams(dp(artSize), dp(artSize)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val listening = column()
        title = text(R.string.book_title, 36, 600).apply { heading(this) }
        author = text(R.string.book_author, 24).apply { setTextColor(muted) }
        playback = text(R.string.connecting, 22, 500).apply { setTextColor(accent); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        listening.addView(title); listening.addView(author, params(8)); listening.addView(playback, params(12))
        chapter = text(R.string.chapter_ready, 26, 500).apply { id = R.id.playback_chapter }; listening.addView(chapter, params(24))
        seek = SeekBar(context).apply {
            id = R.id.seek; max = 1000; minimumHeight = dp(64); splitTrack = false; setPadding(dp(16), 0, dp(16), 0)
            val track = LayerDrawable(arrayOf(rounded(muted, 5), ClipDrawable(rounded(accent, 5), Gravity.START, ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
                for (i in 0..1) { setLayerHeight(i, dp(10)); setLayerGravity(i, Gravity.CENTER_VERTICAL) }
            }
            progressDrawable = track
            thumb = SeekThumb()
            thumbOffset = dp(20)
            progressTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(accent, muted))
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(accent, muted))
            contentDescription = context.getString(R.string.seek_description); controls.add(this)
        }
        listening.addView(seek, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8) })
        val times = row()
        elapsed = text(R.string.time_zero, 24, 500).apply { id = R.id.playback_elapsed; setTextColor(muted); fontFeatureSettings = "'tnum'" }
        remaining = text(R.string.remaining_unknown, 24, 500).apply { id = R.id.playback_remaining; setTextColor(muted); gravity = Gravity.END; fontFeatureSettings = "'tnum'" }
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(remaining, LinearLayout.LayoutParams(0, -2, 1f)); listening.addView(times)
        previous = transportButton(R.string.previous_short, R.id.previous_chapter, R.drawable.qc_previous).apply { contentDescription = context.getString(R.string.previous_chapter) }
        next = transportButton(R.string.next_short, R.id.next_chapter, R.drawable.qc_next).apply { contentDescription = context.getString(R.string.next_chapter) }
        rewind = button(R.string.rewind_short, R.id.rewind).apply { minimumHeight = dp(64);setPadding(dp(8),dp(8),dp(8),dp(8)); contentDescription = context.getString(R.string.rewind_description) }
        play = transportButton(R.string.play, R.id.play, R.drawable.qc_play, true)
        pause = transportButton(R.string.pause, R.id.pause, R.drawable.qc_pause, true).apply { contentDescription = context.getString(R.string.pause_description) }
        val actions = row().apply { gravity = if (wide) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER }
        val primary = FrameLayout(context)
        primary.addView(play, FrameLayout.LayoutParams(-1, dp(88))); primary.addView(pause, FrameLayout.LayoutParams(-1, dp(88)))
        pause.visibility = View.GONE
        if (narrow) for (b in listOf(previous, next)) { val icon = (b.text as android.text.Spanned).getSpans(0,b.text.length,android.text.style.ImageSpan::class.java).first().drawable; val t=android.text.SpannableString(" ");t.setSpan(android.text.style.ImageSpan(icon),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);b.text=t }
        for ((v,w) in listOf(previous to if(narrow)64 else 120, rewind to if(narrow)64 else 88, primary to if(narrow)120 else 176, next to if(narrow)64 else 120)) {
            if (v is Button) quietAction(v)
            actions.addView(v, LinearLayout.LayoutParams(dp(w),dp(if(v===primary)88 else 64)).apply { if(actions.childCount>0)marginStart=dp(if(narrow)8 else 12) })
        }
        listening.addView(actions, params(20))
        recovery = text(R.string.recovery_idle, 20).apply { setTextColor(muted); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        listening.addView(recovery, params(16))
        keepPaused = button(R.string.keep_paused, R.id.keep_paused).apply { minimumHeight=dp(64);visibility=View.GONE;setOnClickListener{pause.performClick()};quietAction(this) }
        listening.addView(keepPaused, params(8))
        val links = row()
        links.addView(link(R.string.browse_chapters, R.id.open_chapters, "chapters"), LinearLayout.LayoutParams(0, dp(64), 1f))
        links.addView(link(R.string.smart_resume, R.id.open_narrative, "narrative"), LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(12) })
        listening.addView(links, params(20))
        prepareShortcut = button(R.string.prepare, R.id.prepare_shortcut).apply { minimumHeight = dp(64) }; listening.addView(prepareShortcut, params(12))
        player.addView(listening, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(40) } else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        addPage("player", player)

        val home = LinearLayout(context).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val homeArtSize = if(wide)artSize else if(narrow)208 else 288
        homeArt = cover(homeArtSize); home.addView(homeArt, LinearLayout.LayoutParams(dp(homeArtSize), dp(homeArtSize)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val homeInfo = column()
        homeInfo.addView(text(R.string.continue_heading, 24, 500).apply { setTextColor(muted) })
        homeTitle = text(R.string.book_title, 40, 600); homeInfo.addView(homeTitle, params(16))
        homeAuthor = text(R.string.book_author, 24).apply { setTextColor(muted) }; homeInfo.addView(homeAuthor, params(8))
        homeChapter = text(R.string.chapter_ready, 24).apply { setTextColor(muted) }; homeInfo.addView(homeChapter, params(12))
        continueListening = button(R.string.continue_listening, R.id.continue_listening, true).apply { minimumHeight = dp(88); textSize = 24f }
        continueListening.isActivated = true
        homeInfo.addView(continueListening, LinearLayout.LayoutParams(dp(if (narrow) 280 else 320), dp(88)).apply { topMargin = dp(32) })
        homeInfo.addView(link(R.string.browse_library, R.id.home_library, "library"), LinearLayout.LayoutParams(dp(if (narrow) 280 else 360), dp(80)).apply { topMargin = dp(12) })
        home.addView(homeInfo, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(48) } else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) }); addPage("home", home)

        val library = readableColumn()
        library.addView(text(R.string.library_description, 22).apply { setTextColor(muted) })
        val book = button(R.string.library_book, R.id.library_book).apply { minimumHeight = dp(176); textSize = 26f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setOnClickListener { showPage("chapters") } }
        val thumb = context.getDrawable(R.drawable.cover_story)!!.apply { setBounds(0, 0, dp(120), dp(120)) }
        book.setCompoundDrawablesRelative(thumb, null, null, null); book.compoundDrawablePadding = dp(28)
        library.addView(book, params(28))
        localAudio = button(R.string.imported_audio, R.id.local_audio).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }; library.addView(localAudio, params(16))
        library.addView(link(R.string.content_preferences, R.id.library_settings, "settings"), params(24)); addPage("library", library)

        val chapters = readableColumn()
        chapters.addView(text(R.string.book_title, 30, 500)); chapters.addView(text(R.string.chapter_selection_notice, 22).apply { setTextColor(muted) }, params(12))
        chapterOne = button(R.string.chapter_one, R.id.chapter_one).apply { minimumHeight = dp(96); textSize = 24f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; contentDescription = context.getString(R.string.select_chapter_one) }
        chapterTwo = button(R.string.chapter_two, R.id.chapter_two).apply { minimumHeight = dp(96); textSize = 24f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; contentDescription = context.getString(R.string.select_chapter_two) }
        chapters.addView(chapterOne, params(32)); chapters.addView(chapterTwo, params(16)); addPage("chapters", chapters)

        val narrative = readableColumn()
        narrativeReadiness = text(R.string.narrative_not_prepared, 30, 600).apply { id = R.id.narrative_readiness }
        narrativeCoverage = text(R.string.narrative_coverage_unavailable, 22).apply { setTextColor(muted) }
        narrative.addView(narrativeReadiness); narrative.addView(narrativeCoverage, params(16))
        recoveryMode = button(R.string.resume_exact, R.id.recovery_mode).apply { minimumHeight = dp(96); textSize = 24f; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        narrative.addView(recoveryMode, params(32))
        recoveryDetail = text(R.string.resume_exact_detail, 22).apply { setTextColor(muted) }; narrative.addView(recoveryDetail, params(20))
        narrative.addView(text(R.string.smart_resume_guards, 22), params(32))
        narrative.addView(link(R.string.nav_listen, R.id.narrative_listen, "player"), params(32)); addPage("narrative", narrative)

        val road = readableColumn()
        roadAvailability = text(R.string.native_context_unavailable, 30, 600).apply { id = R.id.road_availability }
        road.addView(roadAvailability); road.addView(text(R.string.road_explanation, 22).apply { setTextColor(muted) }, params(16))
        health = text(R.string.source_idle, 22, 500).apply { setTextColor(muted) }; road.addView(health, params(28))
        val sim = column().apply { visibility = if (debug) View.VISIBLE else View.GONE; setPadding(0, dp(32), 0, 0) }
        sim.addView(text(R.string.simulation_title, 24, 600)); sim.addView(text(R.string.simulation_notice, 22).apply { setTextColor(muted) }, params(12))
        simulate = button(R.string.run_simulation, R.id.simulate); stopSimulation = button(R.string.stop_simulation, R.id.stop_simulation)
        sim.addView(simulate, params(24)); sim.addView(stopSimulation, params(12)); road.addView(sim)
        diagnosticContent = column().apply { visibility = View.GONE }
        status = text(R.string.connecting, 20).apply { id = R.id.player_status }
        route = text(R.string.source_idle, 20).apply { id = R.id.route_status }
        log = text(R.string.no_events, 20).apply { id = R.id.event_log; setTextColor(muted) }
        diagnosticContent.addView(status); diagnosticContent.addView(route, params(12)); diagnosticContent.addView(log, params(12))
        road.addView(button(R.string.show_diagnostics, R.id.diagnostics_toggle).apply { visibility = if (debug) View.VISIBLE else View.GONE; setOnClickListener { diagnosticContent.visibility = if (diagnosticContent.visibility == View.VISIBLE) View.GONE else View.VISIBLE } }, params(24)); road.addView(diagnosticContent, params(16)); addPage("road", road)

        val settings = readableColumn()
        appearance = button(if (!context.getSharedPreferences("presentation", 0).getBoolean("day", false)) R.string.day_appearance else R.string.night_appearance, R.id.appearance)
        importAudio = button(R.string.import_audio, R.id.import_audio)
        prepare = button(R.string.prepare, R.id.prepare)
        listOf(appearance,importAudio,prepare).forEach{quietAction(it)}
        settings.addView(appearance); settings.addView(importAudio, params(16)); settings.addView(text(R.string.content_access_limit, 22).apply { setTextColor(muted) }, params(16)); settings.addView(prepare, params(24))
        settings.addView(link(R.string.smart_resume, R.id.options_toggle, "narrative"), params(24))
        settings.addView(link(R.string.road_context, R.id.more_road, "road"), params(16))
        settings.addView(button(R.string.road_context, R.id.demo_toggle).apply { visibility=View.GONE;setOnClickListener{showPage("road")} })
        addPage("settings", settings)

        footer = row().apply{setPadding(dp(24),0,dp(24),0)}
        contextMode = button(R.string.basic_playback,R.id.context_mode).apply{minimumHeight=dp(64);textSize=24f;quietAction(this);setOnClickListener{showPage("road")}}
        footer.addView(contextMode,LinearLayout.LayoutParams(dp(if(narrow)200 else 340),dp(64)))
        parkedNotice = text(R.string.parked_notice,20).apply{setTextColor(muted);gravity=Gravity.END}
        footer.addView(parkedNotice,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(16)});frame.addView(footer)
        restricted = column().apply { setPadding(dp(40), dp(40), dp(40), dp(40)); gravity = Gravity.CENTER; visibility = View.GONE; id = R.id.restricted_surface }
        restricted.addView(text(R.string.parked_only_title, 34, 600))
        restrictedMessage = text(R.string.parked_only_message, 24).apply { setTextColor(muted) }; restricted.addView(restrictedMessage, params(24)); frame.addView(restricted, LinearLayout.LayoutParams(-1, 0, 1f))
        showPage("home")
    }

    fun showPage(key: String) {
        if (key !in pages) return
        page = key; pages.forEach { (name, view) -> view.visibility = if (name == key) View.VISIBLE else View.GONE }
        screenHeading.setText(when (key) { "home" -> R.string.screen_home; "library" -> R.string.screen_library; "chapters" -> R.string.screen_chapters; "narrative" -> R.string.screen_narrative; "road" -> R.string.screen_road; "settings" -> R.string.screen_settings; "more" -> R.string.screen_more; else -> R.string.screen_player })
        screenHeading.visibility = if (key in listOf("player","home")) View.GONE else View.VISIBLE
        (pageHost.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (key in listOf("player","home")) 0 else 24)
        body.gravity = if (wide && key in listOf("player", "home")) Gravity.CENTER_VERTICAL else Gravity.TOP
        navigation.forEach { (name, b) -> b.isSelected = name == key || (name == "settings" && key in listOf("narrative","road")) || (name=="library" && key=="chapters") }
        scroll.post { scroll.scrollTo(0, 0) }
    }
    fun present(parked: Boolean, message: String, narrativeReady: Boolean, local: Boolean, playing: Boolean) {
        scroll.visibility = if (parked) View.VISIBLE else View.GONE; nav.visibility = if (parked) View.VISIBLE else View.GONE
        stage.visibility=if(parked)View.VISIBLE else View.GONE;footer.visibility=if(parked)View.VISIBLE else View.GONE
        parkedNotice.visibility = if (parked) View.VISIBLE else View.GONE; restricted.visibility = if (parked) View.GONE else View.VISIBLE
        restrictedMessage.text = if (parked) context.getString(R.string.parked_only_message) else message
        homeTitle.text = title.text; homeAuthor.text = author.text; homeChapter.text = chapter.text; homeArt.setImageResource(if (local) R.drawable.ic_quietchapter else R.drawable.cover_story)
        narrativeReadiness.setText(if (narrativeReady) R.string.narrative_prepared_title else R.string.narrative_not_prepared)
        narrativeCoverage.setText(if (narrativeReady) R.string.narrative_coverage_ready else R.string.narrative_coverage_unavailable)
        val simulated=contextMode.text.contains("SIMULATED")
        contextMode.contentDescription=contextMode.text
        contextMode.setText(if(simulated)R.string.simulation_title else R.string.native_context_unavailable)
        contextMode.setTextColor(if(simulated)accent else muted)
        roadAvailability.setText(if (simulated) R.string.simulation_title else R.string.native_context_unavailable)
        activateAction(play, !playing); activateAction(pause, playing)
        play.visibility=if(playing)View.GONE else View.VISIBLE;pause.visibility=if(playing)View.VISIBLE else View.GONE
        keepPaused.visibility=if(playback.text==context.getString(R.string.state_auto_paused))View.VISIBLE else View.GONE
        if(homeActionPlaying!=playing){
            val d=context.getDrawable(if(playing)R.drawable.qc_pause else R.drawable.qc_play)!!.mutate().apply{setBounds(0,0,dp(36),dp(36));setTintList(actionColors(true))}
            continueListening.setCompoundDrawablesRelative(d,null,null,null);continueListening.compoundDrawablePadding=dp(12);homeActionPlaying=playing
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) { chapterOne.stateDescription = if (chapterOne.isSelected) context.getString(R.string.current_chapter) else null; chapterTwo.stateDescription = if (chapterTwo.isSelected) context.getString(R.string.current_chapter) else null }
    }
    fun saveState(out: Bundle) { out.putString("v2page", page); out.putInt("contentScrollY", scroll.scrollY); out.putBoolean("diagnosticsExpanded", diagnosticContent.visibility == View.VISIBLE) }
    fun restoreState(state: Bundle?) { if (state == null) return; showPage(state.getString("v2page") ?: "player"); if (debug && state.getBoolean("diagnosticsExpanded")) diagnosticContent.visibility = View.VISIBLE; scroll.post { scroll.scrollTo(0, state.getInt("contentScrollY")) } }

    private fun transportButton(label: Int, id: Int, icon: Int, primary: Boolean = false): Button = button(label, id).apply {
        minimumHeight = dp(if (primary) 88 else 64)
        val drawable = context.getDrawable(icon)!!.mutate().apply { setTintList(actionColors(primary)); setBounds(0, 0, dp(if (primary) 48 else 40), dp(if (primary) 48 else 40)) }
        val caption = android.text.SpannableString("  ${context.getString(label)}")
        caption.setSpan(android.text.style.ImageSpan(drawable, android.text.style.ImageSpan.ALIGN_BOTTOM), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); text = caption
        contentDescription = context.getString(label); setPadding(dp(8), dp(8), dp(8), dp(8))
        if (primary) {
            background = RippleDrawable(ColorStateList.valueOf(color(R.attr.qc_ripple)), StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), rounded(color(R.attr.qc_disabled), 24))
                addState(intArrayOf(android.R.attr.state_pressed), rounded(color(R.attr.qc_selected), 24, accent))
                addState(intArrayOf(android.R.attr.state_focused), rounded(color(R.attr.qc_surface), 24, accent))
                addState(intArrayOf(android.R.attr.state_activated), rounded(accent, 24))
                addState(intArrayOf(), rounded(color(R.attr.qc_surface), 24))
            }, null)
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_activated), intArrayOf()), intArrayOf(muted, ink, ink, color(R.attr.qc_background), ink)))
        }
    }
    private fun activateAction(b: Button, active: Boolean) {
        if (b.isActivated == active && b.tag == "action-colored") return
        b.isActivated = active; b.tag = "action-colored"
        (b.text as? android.text.Spanned)?.getSpans(0, b.text.length, android.text.style.ImageSpan::class.java)?.forEach { it.drawable.setTintList(actionColors(true)); it.drawable.state = b.drawableState }
        b.invalidate()
    }
    private fun addPage(name: String, view: View) { pages[name] = view; pageHost.addView(view, (view.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(-1, -2)); view.tag = "qc_screen_$name" }
    private fun readableColumn(): LinearLayout {
        val targetWidth = if (wide) dp(minOf(1120, width - 64)) else -1
        return column().apply { layoutParams = FrameLayout.LayoutParams(targetWidth, -2) }
    }
    private fun cover(size: Int) = ImageView(context).apply { setImageResource(R.drawable.cover_story); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = context.getString(R.string.artwork_description); layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)) }
    private fun link(label: Int, id: Int, destination: String) = button(label, id).apply { minimumHeight = dp(80); gravity = Gravity.START or Gravity.CENTER_VERTICAL; quietAction(this);setOnClickListener { showPage(destination) } }
    private fun quietAction(b:Button){
        b.background=RippleDrawable(ColorStateList.valueOf(color(R.attr.qc_ripple)),StateListDrawable().apply{
            addState(intArrayOf(android.R.attr.state_focused),rounded(color(R.attr.qc_surface),12,accent))
            addState(intArrayOf(android.R.attr.state_pressed),rounded(color(R.attr.qc_selected),12))
            addState(intArrayOf(),rounded(android.graphics.Color.TRANSPARENT,12))
        },null)
    }
    private fun color(id: Int): Int { val v = android.util.TypedValue(); check(context.theme.resolveAttribute(id, v, true)); return v.data }
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density + .5f).toInt()
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun params(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun heading(v: View) { if (android.os.Build.VERSION.SDK_INT >= 28) v.isAccessibilityHeading = true }
    private fun text(label: Int, size: Int, weight: Int = 400) = QuietText(context, weight).apply { setText(label); textSize = size.toFloat(); setTextColor(ink); includeFontPadding = false; setLineSpacing(dp(3).toFloat(), 1f) }
    private fun rounded(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(2), stroke) }
    private fun button(label: Int, resId: Int, primary: Boolean = false): Button = QuietButton(context).apply {
        id = resId; setText(label); textSize = 24f; isAllCaps = false; stateListAnimator = null; elevation = 0f; minimumHeight = dp(88); minimumWidth = 0; minWidth = 0; includeFontPadding = false
        setPadding(dp(24), dp(16), dp(24), dp(16)); setLineSpacing(dp(3).toFloat(), 1f)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(muted, ink, ink, if (primary) color(R.attr.qc_background) else ink)))
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(color(R.attr.qc_disabled), 12))
            addState(intArrayOf(android.R.attr.state_focused), rounded(color(R.attr.qc_surface), 12, accent))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(color(R.attr.qc_selected), 12, accent))
            addState(intArrayOf(android.R.attr.state_selected), rounded(color(R.attr.qc_selected), 12, accent))
            addState(intArrayOf(), rounded(if (primary) accent else color(R.attr.qc_surface), 12))
        }
        background = RippleDrawable(ColorStateList.valueOf(color(R.attr.qc_ripple)), states, null); controls.add(this)
    }
    private fun actionColors(primary: Boolean) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_activated), intArrayOf()),
        intArrayOf(muted, ink, ink, if (primary) color(R.attr.qc_background) else ink, ink))
    private inner class TabBackground : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
        override fun draw(canvas: Canvas) {
            val selected = state.contains(android.R.attr.state_selected); val focused = state.contains(android.R.attr.state_focused); val pressed = state.contains(android.R.attr.state_pressed)
            if (focused || pressed) { paint.color = color(R.attr.qc_selected); canvas.drawRoundRect(RectF(bounds), dp(10).toFloat(), dp(10).toFloat(), paint) }
            if (selected) { paint.color = accent; val r=if(wide)RectF(bounds.left.toFloat(),bounds.top.toFloat()+dp(12),bounds.left.toFloat()+dp(4),bounds.bottom.toFloat()-dp(12))else RectF(bounds.left.toFloat()+dp(20), bounds.bottom.toFloat()-dp(4), bounds.right.toFloat()-dp(20), bounds.bottom.toFloat());canvas.drawRoundRect(r,dp(2).toFloat(),dp(2).toFloat(),paint) }
            if (focused) { paint.color = accent; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); canvas.drawRoundRect(RectF(bounds).apply { inset(dp(1).toFloat(),dp(1).toFloat()) },dp(10).toFloat(),dp(10).toFloat(),paint);paint.style=Paint.Style.FILL }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Platform Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    /** Native SeekBar retains touch/accessibility behavior; only the handle's visual state changes. */
    private inner class SeekThumb : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth() = dp(40)
        override fun getIntrinsicHeight() = dp(40)
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
        override fun draw(canvas: Canvas) {
            val enabled = state.contains(android.R.attr.state_enabled)
            val active = enabled && (state.contains(android.R.attr.state_pressed) || state.contains(android.R.attr.state_focused))
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
            if (active) {
                paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat()
                canvas.drawCircle(x, y, dp(18).toFloat(), paint); paint.style = Paint.Style.FILL
            }
            paint.color = if (enabled) accent else muted
            canvas.drawCircle(x, y, dp(if (active) 16 else 15).toFloat(), paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Platform Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

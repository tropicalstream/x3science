package com.x3science.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * x3science — an invisible field-trip companion for the RayNeo X3 Pro.
 *
 * Nothing is projected: the screen stays black (waveguide pixels off), the
 * world-facing camera watches, and a great scientist murmurs observations
 * about whatever the wearer is looking at.
 *
 *   single tap ..... pause / resume commentary
 *   double tap ..... settings (the only visible screen)
 *   swipe .......... switch scientist (brief overlay shows who's speaking)
 *   left pad ....... volume (hardware behavior)
 */
class MainActivity : Activity() {

    private lateinit var store: SciStore
    private lateinit var camera: EyeCamera
    private lateinit var brain: ScienceBrain
    private lateinit var speech: SpeechEngine

    private lateinit var binocular: BinocularSbsLayout
    private lateinit var viewport: FrameLayout
    private lateinit var settingsList: LinearLayout
    private lateinit var settingsPanel: ScrollView
    private lateinit var flashText: TextView          // transient scientist / pause flash

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var paused = false
    @Volatile private var cycleBusy = false        // one analysis+synthesis in flight at a time
    @Volatile private var awaitingFrame = false
    @Volatile private var generation = 0           // bumped on pause/switch: stale results are dropped
    private var pendingAudio: java.io.File? = null  // next beat, synthesized and waiting
    private var playbackActive = false
    private var settingsOpen = false

    // ---- Lifecycle -----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SciStore(this)
        brain = ScienceBrain(store)
        speech = SpeechEngine(this, store)
        camera = EyeCamera(this)
        camera.onFrame = { jpeg -> onFrame(jpeg) }
        camera.onError = { msg -> main.post { flashOverlay(msg, 2500) } }
        speech.onNotice = { msg -> flashOverlay(msg, 2400) }

        buildUi()
        wireGestures()

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "x3science:pipeline")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startPipeline()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 11)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 11 && results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startPipeline()
        } else if (code == 11) {
            flashOverlay("x3science needs the camera to see the world", 4000)
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from wear-sensor doze / task switch: Android cuts camera
        // access while the activity isn't visible, so reopen and rejoin the loop.
        if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!camera.isOpen) camera.open()
            cycleBusy = false
            awaitingFrame = false
            if (!paused) scheduleNext(2_500L)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        speech.stop()
        camera.close()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    // ---- The commentary loop ----------------------------------------------------

    private fun startPipeline() {
        camera.open()
        val s = Scientists.ALL[store.scientistIndex]
        flashOverlay("${s.emoji} ${s.field} — ${s.name}\nsingle tap: pause · double tap: settings", 3200)
        scheduleNext(4_000L)
    }

    private fun scheduleNext(delayMs: Long) {
        main.removeCallbacks(cycleRunnable)
        main.postDelayed(cycleRunnable, delayMs)
    }

    /**
     * The lecture pipeline. The trick that makes it feel continuous: while one
     * beat is being SPOKEN, the next frame is already captured, analyzed, and
     * synthesized. When playback ends, the next beat is (usually) sitting ready
     * — the configured gap is pure breathing room, not network round-trips.
     */
    private val cycleRunnable = Runnable {
        if (paused || cycleBusy) return@Runnable
        pendingAudio?.let { ready ->
            // A beat is already synthesized but nothing is playing (e.g. resumed
            // from doze mid-pipeline): play it rather than analyzing again.
            if (!playbackActive) { pendingAudio = null; playBeat(ready) }
            return@Runnable
        }
        if (!camera.isOpen) {
            // Not fatal: the camera may still be configuring, or the OS pulled it
            // during a doze. Nudge it and try again — never let the loop die.
            camera.open()
            scheduleNext(2_500L)
            return@Runnable
        }
        cycleBusy = true
        awaitingFrame = true
        camera.capture()
        // Watchdog for the capture→frame hop ONLY — never the full cycle.
        main.postDelayed({
            if (awaitingFrame) { awaitingFrame = false; cycleBusy = false; scheduleNext(4_000L) }
        }, 8_000L)
    }

    private fun onFrame(jpeg: ByteArray) {
        awaitingFrame = false
        if (paused) { cycleBusy = false; return }
        val gen = generation
        val index = store.scientistIndex
        val scientist = Scientists.ALL[index]
        val level = store.level(index)
        Thread {
            val audio = brain.comment(jpeg, scientist, level).getOrElse { e ->
                main.post {
                    cycleBusy = false
                    if (gen == generation && !paused) {
                        flashOverlay(e.message ?: "Commentary failed", 2600)
                        scheduleNext(12_000L)
                    }
                }
                return@Thread
            }.let { text -> speech.synthesize(text, index) }
            main.post {
                cycleBusy = false
                if (audio == null) { if (gen == generation && !paused) scheduleNext(10_000L); return@post }
                if (gen != generation || paused) { audio.delete(); return@post }
                if (playbackActive) pendingAudio = audio else playBeat(audio)
            }
        }.start()
    }

    /** Play one lecture beat and immediately start preparing the next one. */
    private fun playBeat(audio: java.io.File) {
        playbackActive = true
        val gen = generation
        speech.play(audio) {
            playbackActive = false
            if (gen != generation || paused) { pendingAudio?.delete(); pendingAudio = null; return@play }
            val next = pendingAudio
            pendingAudio = null
            val gapMs = store.intervalSec * 1000L
            if (next != null) main.postDelayed({
                if (paused || gen != generation) next.delete() else playBeat(next)
            }, gapMs)
            else scheduleNext(gapMs)
        }
        // Prefetch: a beat lasts ~10-25s; kick the next analysis shortly after
        // this one starts so it's ready (or nearly) when the voice goes quiet.
        main.postDelayed({ if (!paused && gen == generation) cycleRunnable.run() }, 1_500L)
    }

    private fun clearPipeline() {
        generation++
        speech.stop()
        playbackActive = false
        cycleBusy = false
        awaitingFrame = false
        pendingAudio?.delete()
        pendingAudio = null
        main.removeCallbacks(cycleRunnable)
    }

    private fun togglePause() {
        paused = !paused
        if (paused) {
            clearPipeline()
            flashOverlay("⏸ paused — tap to resume", 1600)
        } else {
            flashOverlay("▶", 900)
            scheduleNext(600L)
        }
    }

    private fun switchScientist(delta: Int) {
        store.scientistIndex = store.scientistIndex + delta
        brain.reset()
        clearPipeline()
        val s = Scientists.ALL[store.scientistIndex]
        flashOverlay("${s.emoji} ${s.field}\n${s.name}", 1800)
        if (!paused) scheduleNext(1_900L)
        if (settingsOpen) refreshSettings()
    }

    // ---- UI ------------------------------------------------------------------------

    private fun buildUi() {
        settingsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(30), dp(18), dp(30))
        }
        settingsPanel = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            addView(settingsList, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        flashText = TextView(this).apply {
            textSize = 19f
            setTextColor(0xFF9FE8C8.toInt())
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER
            layoutParams = lp
        }
        viewport = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)   // black = projector dark = invisible
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(settingsPanel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(flashText)
        }
        binocular = BinocularSbsLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(viewport, 0)
            setContentTarget(viewport)
        }
        setContentView(binocular)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    private fun wireGestures() {
        binocular.apply {
            doubleTapHandler = { toggleSettings() }
            logicalClickHandler = { _, _ ->
                if (!settingsOpen) { togglePause(); true } else false
            }
            // Swiping on the dark screen steps through the scientists; the cursor
            // never appears there (suppressed), so nothing lights up.
            menuNavigationActive = { !settingsOpen }
            horizontalStepHandler = { delta -> if (!settingsOpen) switchScientist(delta) }
            cursorSuppressed = { !settingsOpen }
            leftEdgeBackHandler = { if (settingsOpen) toggleSettings() }
            edgeScrollHandler = { dy -> if (settingsOpen) settingsPanel.scrollBy(0, dy * 2) }
            contentInteractionBlocked = { !settingsOpen }
        }
    }

    private fun toggleSettings() {
        settingsOpen = !settingsOpen
        settingsPanel.visibility = if (settingsOpen) View.VISIBLE else View.GONE
        if (settingsOpen) { refreshSettings(); settingsPanel.bringToFront() }
    }

    private fun flashOverlay(text: String, ms: Long) {
        flashText.text = text
        flashText.visibility = View.VISIBLE
        flashText.bringToFront()
        main.removeCallbacks(hideOverlay)
        main.postDelayed(hideOverlay, ms)
    }
    private val hideOverlay = Runnable { flashText.visibility = View.GONE }
    private fun flashOverlay(text: String, ms: Int) = flashOverlay(text, ms.toLong())

    // ---- Settings ---------------------------------------------------------------------

    private fun refreshSettings() {
        settingsList.removeAllViews()
        settingsList.addView(title("🔬 x3science"))
        val s = Scientists.ALL[store.scientistIndex]
        settingsList.addView(hint(
            "${if (paused) "⏸ Paused" else "▶ Commenting"} as ${s.name}. " +
                "Outside this menu nothing is displayed: single tap pauses, swipe changes scientist."))

        settingsList.addView(title("🔊 Volume"))
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val volLabel = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE)
            text = volText(am)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun volBtn(lbl: String, dir: Int) = Button(this).apply {
            text = lbl; isAllCaps = false; textSize = 16f
            minWidth = 0; minimumWidth = 0
            setOnClickListener {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                volLabel.text = volText(am)
            }
        }
        volRow.addView(volLabel); volRow.addView(volBtn("−", AudioManager.ADJUST_LOWER)); volRow.addView(volBtn("＋", AudioManager.ADJUST_RAISE))
        settingsList.addView(volRow)

        settingsList.addView(title("🧑‍🔬 Scientist · tap 🎓 to set each one's audience"))
        Scientists.ALL.forEachIndexed { i, sci ->
            val selected = i == store.scientistIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(3), 0, dp(3)) }
            }
            row.addView(Button(this).apply {
                text = "${sci.emoji}  ${sci.field} — ${sci.name}${if (selected) "   ✓" else ""}"
                isAllCaps = false; textSize = 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(if (selected) 0xFF9FE8C8.toInt() else Color.WHITE)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    store.scientistIndex = i
                    brain.reset()
                    clearPipeline()
                    refreshSettings()
                    if (!paused) scheduleNext(1_200L)
                }
            })
            row.addView(Button(this).apply {
                text = "🎓 ${SciStore.LEVELS[store.level(i)]}"
                isAllCaps = false; textSize = 11f
                minWidth = 0; minimumWidth = 0
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(4) }
                setOnClickListener {
                    val next = (store.level(i) + 1) % SciStore.LEVELS.size
                    store.setLevel(i, next)
                    text = "🎓 ${SciStore.LEVELS[next]}"
                    // Re-pitch the running lecture if this scientist is speaking.
                    if (i == store.scientistIndex) brain.reset()
                    flashOverlay("${sci.name}: ${SciStore.LEVELS[next]}", 1400)
                }
            })
            settingsList.addView(row)
        }

        settingsList.addView(title("⏱ Pace"))
        val paceLabel = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE)
            text = "Quiet gap between comments: ${store.intervalSec}s"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val paceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun paceBtn(lbl: String, delta: Int) = Button(this).apply {
            text = lbl; isAllCaps = false; textSize = 16f
            minWidth = 0; minimumWidth = 0
            setOnClickListener {
                store.intervalSec = store.intervalSec + delta
                paceLabel.text = "Quiet gap between comments: ${store.intervalSec}s"
            }
        }
        paceRow.addView(paceLabel); paceRow.addView(paceBtn("−", -2)); paceRow.addView(paceBtn("＋", 2))
        settingsList.addView(paceRow)

        settingsList.addView(title("🗣 Speech engine"))
        settingsList.addView(Button(this).apply {
            text = if (store.engine == "gemini") "Gemini TTS (single voice) — tap for fish.audio"
            else "fish.audio (a voice per scientist) — tap for Gemini TTS"
            isAllCaps = false; textSize = 13f
            setOnClickListener { store.engine = if (store.engine == "gemini") "fish" else "gemini"; refreshSettings() }
        })

        settingsList.addView(title("🔑 API keys"))
        settingsList.addView(keyField("Gemini API key", SciStore.K_GEMINI))
        settingsList.addView(keyField("fish.audio API key", SciStore.K_FISH))
        settingsList.addView(hint("Keys stay on the glasses and are never shown once saved. Each scientist's fish voice ID can be replaced any time below."))
        Scientists.ALL.forEachIndexed { i, sci ->
            settingsList.addView(keyField("${sci.emoji} ${sci.name} voice ID", "voice_$i", secret = false))
        }

        settingsList.addView(Button(this).apply {
            text = "Close  (or double-tap)"
            isAllCaps = false; textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(14), 0, 0) }
            setOnClickListener { toggleSettings() }
        })
    }

    private fun volText(am: AudioManager): String {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "Media volume: $cur / $max"
    }

    /** Family policy: a saved secret is never displayed; blank save keeps it. */
    private fun keyField(label: String, key: String, secret: Boolean = true): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        row.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(0xFF8B949E.toInt()) })
        val saved = store.getString(key, "")
        val et = EditText(this).apply {
            if (!secret) { setText(saved); setSelection(text.length) }
            textSize = 14f; setTextColor(Color.WHITE)
            setBackgroundColor(0xFF14181C.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            inputType = if (secret)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            isFocusableInTouchMode = true
            showSoftInputOnFocus = false
            hint = when {
                secret && saved.isNotBlank() -> "•••••••• saved · paste to replace"
                secret -> "not set · paste with scrcpy (Ctrl+V)"
                else -> "paste with scrcpy (Ctrl+V)"
            }
        }
        row.addView(et)
        row.addView(Button(this).apply {
            text = "Save"; isAllCaps = false; textSize = 12f
            setOnClickListener {
                val v = et.text.toString().trim()
                if (secret && v.isBlank()) {
                    flashOverlay(if (saved.isBlank()) "Nothing pasted yet" else "Kept the saved key", 1500)
                } else {
                    store.putString(key, v)
                    if (secret) { et.setText(""); et.hint = "•••••••• saved · paste to replace" }
                    flashOverlay("Saved ✓", 1200)
                }
                et.clearFocus()
            }
        })
        return row
    }

    // ---- Helpers -----------------------------------------------------------------------

    private fun title(text: String) = TextView(this).apply {
        this.text = text; textSize = 16f; setTextColor(0xFF9FE8C8.toInt())
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(2), dp(14), dp(2), dp(6))
    }
    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(0xFF8B949E.toInt())
        setPadding(dp(2), dp(2), dp(2), dp(8))
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

package com.x3science.app

import android.content.Context
import java.io.File

/**
 * Settings + secrets. API keys are entered in Settings (masked once saved) or
 * seeded once from files/keys.import — a line-per-value file pushed over adb
 * that is consumed and DELETED on first read. Keys live only in on-device
 * prefs; they are never hardcoded, logged, or bundled in the APK.
 *
 * keys.import format (any line may be blank to skip):
 *   line 1: Gemini API key
 *   line 2: fish.audio API key
 *   lines 3..8: fish voice IDs for the six scientists, in Scientists.ALL order
 */
class SciStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("x3science", Context.MODE_PRIVATE)

    companion object {
        const val K_GEMINI = "gemini_key"
        const val K_FISH = "fish_key"
        const val K_SCIENTIST = "scientist_index"
        const val K_INTERVAL = "interval_sec"
        const val K_ENGINE = "tts_engine"        // "fish" | "gemini"
        private const val VOICE_PREFIX = "voice_"
        val LEVELS = listOf("Elementary", "Primary", "Undergraduate", "Graduate", "PhD")
    }

    init { importSeedFile() }

    private fun importSeedFile() {
        val f = File(context.filesDir, "keys.import")
        if (!f.isFile) return
        runCatching {
            val lines = f.readLines().map { it.trim() }
            lines.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { putString(K_GEMINI, it) }
            lines.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { putString(K_FISH, it) }
            for (i in Scientists.ALL.indices) {
                lines.getOrNull(2 + i)?.takeIf { it.isNotBlank() }?.let { setVoice(i, it) }
            }
        }
        f.delete()
    }

    fun geminiKey(): String = getString(K_GEMINI, "")
    fun fishKey(): String = getString(K_FISH, "")

    fun voice(index: Int): String = getString("$VOICE_PREFIX$index", "")
    fun setVoice(index: Int, id: String) = putString("$VOICE_PREFIX$index", id.trim())

    /** Auto-found substitute voice when the preferred ID stops working. */
    fun substituteVoice(index: Int): String = getString("voice_sub_$index", "")
    fun setSubstituteVoice(index: Int, id: String) = putString("voice_sub_$index", id.trim())

    // Per-scientist audience level: 0 elementary · 1 primary · 2 undergraduate
    // (default, the original behavior) · 3 graduate · 4 PhD.
    fun level(index: Int): Int = getInt("level_$index", 2).coerceIn(0, LEVELS.lastIndex)
    fun setLevel(index: Int, level: Int) = putInt("level_$index", level.coerceIn(0, LEVELS.lastIndex))

    var scientistIndex: Int
        get() = getInt(K_SCIENTIST, 0).coerceIn(0, Scientists.ALL.lastIndex)
        set(v) = putInt(K_SCIENTIST, v.mod(Scientists.ALL.size))

    var intervalSec: Int
        get() = getInt(K_INTERVAL, 4).coerceIn(2, 120)
        set(v) = putInt(K_INTERVAL, v.coerceIn(2, 120))

    var engine: String
        get() = getString(K_ENGINE, "fish")
        set(v) = putString(K_ENGINE, if (v == "gemini") "gemini" else "fish")

    fun getString(key: String, def: String): String = prefs.getString(key, def) ?: def
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
}

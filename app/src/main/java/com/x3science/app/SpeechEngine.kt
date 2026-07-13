package com.x3science.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Speaks commentary through one of two engines, chosen in Settings:
 *  - fish.audio (default): the newest s2.1 model, one voice per scientist.
 *  - Gemini TTS: the gemini-2.5-flash-preview-tts speech model (returns raw
 *    24 kHz PCM which we wrap in a WAV header for MediaPlayer).
 */
class SpeechEngine(private val context: Context, private val store: SciStore) {
    companion object {
        private const val TAG = "x3science"
        private const val FISH_MODEL = "s2.1-pro-free"   // newest fish generation, free-tier variant
        private const val GEMINI_TTS_MODEL = "gemini-2.5-flash-preview-tts"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    /** User-visible information (voice substitution) — invoked on the main thread. */
    var onNotice: ((String) -> Unit)? = null
    private val substituteNotified = mutableSetOf<Int>()

    val isSpeaking: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** Synthesize to a temp audio file WITHOUT playing — this is what lets the
     *  next lecture beat be prepared while the current one is still being spoken.
     *  Blocking; call off the UI thread. Null on failure (logged). */
    fun synthesize(text: String, scientistIndex: Int): File? =
        runCatching {
            if (store.engine == "gemini") geminiTts(text) else fishTts(text, scientistIndex)
        }.onSuccess { Log.i(TAG, "tts ok engine=${store.engine}") }
            .onFailure { Log.w(TAG, "tts failed: ${it.message}") }.getOrNull()

    /** Play a synthesized file. onDone fires on the main thread at the end of
     *  playback (or on failure); the file is deleted either way. */
    fun play(audio: File, onDone: () -> Unit) {
        stop()
        main.post {
            runCatching {
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                mp.setDataSource(audio.absolutePath)
                mp.setOnCompletionListener { cleanup(mp, audio); onDone() }
                mp.setOnErrorListener { _, _, _ -> cleanup(mp, audio); onDone(); true }
                mp.prepare(); mp.start()
            }.onFailure { audio.delete(); onDone() }
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    private fun cleanup(mp: MediaPlayer, f: File) {
        runCatching { mp.release() }
        if (player === mp) player = null
        f.delete()
    }

    // ---- fish.audio -----------------------------------------------------------

    /** Voice-specific failure (bad/retired reference_id) — triggers the fallback. */
    private class VoiceUnavailable(code: Int) : Exception("fish.audio voice error HTTP $code")

    private fun fishTts(text: String, scientistIndex: Int): File? {
        val key = store.fishKey().trim()
        if (key.isBlank()) throw IllegalStateException("Add a fish.audio key in Settings")
        val preferred = store.voice(scientistIndex).trim()
        val substitute = store.substituteVoice(scientistIndex).trim()

        // Preferred voice first; on a voice-specific failure (or no ID at all),
        // fall back to a cached substitute or search the library for a fresh
        // professional-sounding one, remembering it for next time.
        if (preferred.isNotBlank()) {
            try { return fishCall(key, preferred, text) } catch (e: VoiceUnavailable) {
                Log.w(TAG, "voice $preferred unavailable, using fallback: ${e.message}")
            }
        }
        val fallback = substitute.ifBlank {
            findProfessionalVoice(key)?.also { store.setSubstituteVoice(scientistIndex, it) }
        } ?: throw IllegalStateException("No usable fish.audio voice for this scientist")
        if (substituteNotified.add(scientistIndex)) {
            val who = Scientists.ALL.getOrNull(scientistIndex)?.name ?: "This scientist"
            main.post { onNotice?.invoke("$who's voice is unavailable — using a professional stand-in") }
        }
        return fishCall(key, fallback, text)
    }

    private fun fishCall(key: String, voice: String, text: String): File {
        val payload = JSONObject()
            .put("text", text.take(1400))
            .put("format", "mp3").put("mp3_bitrate", 128).put("normalize", true)
            .put("latency", "normal").put("reference_id", voice)
        val request = Request.Builder().url("https://api.fish.audio/v1/tts")
            .header("Authorization", "Bearer $key").header("model", FISH_MODEL)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                // 401/402/429 are account/limit problems; anything else on a TTS
                // call is overwhelmingly a reference_id that no longer resolves.
                if (resp.code !in setOf(401, 402, 429)) throw VoiceUnavailable(resp.code)
                throw IllegalStateException("fish.audio HTTP ${resp.code}")
            }
            val f = File.createTempFile("sci_", ".mp3", context.cacheDir)
            f.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
            f
        }
    }

    /** Search the public voice library for a professional-sounding stand-in. */
    private fun findProfessionalVoice(key: String): String? {
        val request = Request.Builder()
            .url("https://api.fish.audio/model?page_size=8&sort_by=task_count&title=professional")
            .header("Authorization", "Bearer $key").get().build()
        return runCatching {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val items = JSONObject(resp.body?.string().orEmpty()).optJSONArray("items") ?: return null
                (0 until items.length()).firstNotNullOfOrNull { i ->
                    items.optJSONObject(i)?.optString("_id")?.takeIf { it.isNotBlank() }
                }
            }
        }.onSuccess { Log.i(TAG, "fallback voice selected: ${it?.take(8)}…") }
            .onFailure { Log.w(TAG, "voice search failed: ${it.message}") }
            .getOrNull()
    }

    // ---- Gemini TTS -------------------------------------------------------------

    private fun geminiTts(text: String): File? {
        val key = store.geminiKey().trim()
        if (key.isBlank()) throw IllegalStateException("Add a Gemini key in Settings")
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", text.take(1400))))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Charon")))))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_TTS_MODEL:generateContent")
            .header("x-goog-api-key", key)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Gemini TTS HTTP ${resp.code}")
            val b64 = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optJSONObject("inlineData")?.optString("data")
                ?: throw IllegalStateException("Gemini TTS returned no audio")
            val pcm = Base64.decode(b64, Base64.DEFAULT)
            val f = File.createTempFile("sci_", ".wav", context.cacheDir)
            f.writeBytes(wavHeader(pcm.size, 24_000, 1, 16) + pcm)
            f
        }
    }

    /** Minimal RIFF/WAVE header for raw 16-bit PCM. */
    private fun wavHeader(dataLen: Int, rate: Int, channels: Int, bits: Int): ByteArray {
        val byteRate = rate * channels * bits / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(rate); putInt(byteRate); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(dataLen)
        }.array()
    }
}

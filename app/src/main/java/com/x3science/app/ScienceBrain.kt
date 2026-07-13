package com.x3science.app

import android.util.Base64
import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The eyes-to-words pipeline: a camera JPEG goes to a Gemini vision model with
 * the active scientist's persona, and a couple of spoken-word sentences come
 * back. Keeps a short memory of recent remarks so the commentary keeps moving
 * instead of re-describing the same desk forever.
 *
 * Model chain uses the -latest aliases (pinned versions get retired) with
 * fall-through on capacity/retirement, as proven across this app family.
 */
class ScienceBrain(private val store: SciStore) {
    companion object {
        private const val TAG = "x3science"
        private val MODELS = listOf("gemini-flash-lite-latest", "gemini-flash-latest", "gemini-2.5-flash")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private val recent = ArrayDeque<String>()

    fun reset() = recent.clear()

    /** How the beat should be pitched, per the scientist's configured audience. */
    private fun audienceLine(level: Int): String = when (level) {
        0 -> "Your listener is a curious elementary-school child: very simple everyday words and short sentences — but stay anchored to the scene. Start from the actual thing in the image, name it plainly (\"see that lamp?\"), and only then compare it to familiar things. Never drift into a generic story that ignores what is visible, and never talk down."
        1 -> "Your listener is a sharp middle-school student: clear simple language, concrete examples, and instantly define any word they might not know."
        3 -> "Your listeners are graduate students: be technically precise, assume a solid foundation, and introduce advanced ideas with brief, sharp clarification."
        4 -> "Your listeners are fellow PhDs at a seminar: full precision and nuance, use the field's terminology freely, and dwell on what is subtle or contested rather than the basics."
        else -> "Your listeners are bright undergraduates: teach college-level ideas in plain high-school vocabulary, and if a technical term slips in, unpack it in the same breath."
    }

    /** Blocking; call off the UI thread. */
    fun comment(jpeg: ByteArray, scientist: Scientist, level: Int): Result<String> = runCatching {
        val key = store.geminiKey().trim()
        require(key.isNotBlank()) { "Add a Gemini key in Settings (double-tap)" }
        val soFar = if (recent.isEmpty())
            "This is the opening beat of the lecture — draw the listener in with something they can see right now."
        else
            "Your lecture so far (continue naturally from it — build on it, never repeat or summarize it):\n" +
                recent.joinToString("\n") { "- $it" }
        val prompt = """
            ${scientist.persona}

            You are mid-lecture, thinking aloud to a bright college class, and the image
            is what you and the listener are looking at through smart glasses right now.

            Speak the NEXT beat of your running lecture: two to four flowing sentences
            (about 50-80 words) that pick up from where you left off and move to a fresh
            detail actually visible in the image. Anchor the beat to ONE concrete object
            or feature you can genuinely see, and name it explicitly early in the beat so
            the listener knows exactly what you are looking at. Always finish your final
            sentence.
            ${audienceLine(level)}

            Plain conversational spoken prose only — no lists, no markdown, no stage
            directions, no greetings, no sign-offs, and never restart the lecture.
            If the scene is dark or unclear, muse in character instead of complaining.

            $soFar
        """.trimIndent()

        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 1.0).put("maxOutputTokens", 700))

        var text = generate(key, payload).trim()
            .replace(Regex("[*_#>`]"), "")          // safety: strip any markdown
            .replace(Regex("\\s+"), " ")
        // Anti-truncation: if the model ran out of tokens mid-sentence, drop the
        // dangling fragment so the spoken beat always ends cleanly. (Keep the raw
        // text when no earlier sentence boundary exists to fall back to.)
        if (text.isNotEmpty() && text.last() !in ".!?…\"”") {
            val lastEnd = text.indexOfLast { it in ".!?…" }
            if (lastEnd >= text.length / 3) text = text.substring(0, lastEnd + 1)
        }
        require(text.isNotBlank()) { "Gemini returned no commentary" }
        Log.i(TAG, "commentary ok: ${text.length} chars as ${scientist.name}")
        recent.addLast(text)
        while (recent.size > 8) recent.removeFirst()
        text
    }.onFailure { Log.w(TAG, "comment failed: ${it.message}") }

    private fun generate(key: String, payload: JSONObject): String {
        var lastFailure: Exception? = null
        for ((index, model) in MODELS.withIndex()) {
            val result = runCatching { generateWithModel(model, key, payload) }
            result.getOrNull()?.let { return it }
            val failure = result.exceptionOrNull() as? Exception ?: Exception("Gemini request failed")
            lastFailure = failure
            if (!isTransient(failure.message.orEmpty())) throw failure
            if (index < MODELS.lastIndex) Thread.sleep(250L * (index + 1))
        }
        throw IllegalStateException("Gemini is temporarily busy. Commentary will retry.", lastFailure)
    }

    private fun generateWithModel(model: String, key: String, payload: JSONObject): String {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }
                    .getOrDefault("Gemini HTTP ${response.code}")
                throw IllegalStateException("HTTP ${response.code}: ${message.take(160)}")
            }
            val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini returned no candidates")
            buildString {
                for (i in 0 until parts.length())
                    parts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }?.let { append(it) }
            }.trim().takeIf { it.isNotBlank() } ?: throw IllegalStateException("Gemini returned empty text")
        }
    }

    private fun isTransient(message: String): Boolean {
        val lower = message.lowercase()
        return "http 429" in lower || "http 500" in lower || "http 503" in lower ||
            "overloaded" in lower || "temporar" in lower || "unavailable" in lower ||
            "http 404" in lower || "not found" in lower || "not supported" in lower
    }
}

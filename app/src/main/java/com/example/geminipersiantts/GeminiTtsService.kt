package com.example.geminipersiantts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Android TTS engine backed by the Gemini API native TTS model.
 * Gemini returns raw 16-bit PCM, mono, 24 kHz audio as base64 inlineData.
 */
class GeminiTtsService : TextToSpeechService() {

    companion object {
        const val PREFS = "gemini_tts"
        const val KEY_API = "api_key"
        const val KEY_VOICE = "voice"
        const val DEFAULT_VOICE = "Kore"
        const val MODEL = "gemini-2.5-flash-preview-tts"
        private const val SAMPLE_RATE = 24000
        private const val TAG = "GeminiTts"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(2, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        // Warm up TLS/HTTP2 connection so the first sentence doesn't pay handshake cost.
        Thread {
            try {
                client.newCall(Request.Builder()
                    .url("https://generativelanguage.googleapis.com/").head().build()).execute().close()
            } catch (_: Exception) {}
        }.start()
    }

    @Volatile private var stopped = false

    /** Small LRU cache so repeated sentences (re-reads, rewinds) play instantly. */
    private val cache = object : android.util.LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (lang == "fas" || lang == "fa" || lang == "per") TextToSpeech.LANG_AVAILABLE
        else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> = arrayOf("fas", "IRN", "")

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onStop() { stopped = true }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API, "").orEmpty()
        val voice = prefs.getString(KEY_VOICE, DEFAULT_VOICE).orEmpty().ifEmpty { DEFAULT_VOICE }
        val text = request.charSequenceText?.toString() ?: ""
        if (apiKey.isEmpty() || text.isBlank()) { callback.error(); return }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", "Read aloud in Persian: " + text)))))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
            })
        }.toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:streamGenerateContent?alt=sse"
        val cacheKey = "$voice|$text"
        cache.get(cacheKey)?.let { pcm -> play(pcm, callback); return }

        var started = false
        val all = java.io.ByteArrayOutputStream()
        try {
            for (attempt in 0 until 3) {
                if (stopped) { if (started) callback.done() else callback.error(); return }
                val req = Request.Builder().url(url)
                    .header("x-goog-api-key", apiKey)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                val t0 = System.currentTimeMillis()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 429 || resp.code >= 500) {
                        val raw = resp.body?.string().orEmpty()
                        Log.w(TAG, "HTTP ${resp.code} attempt $attempt: $raw")
                        val m = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(raw)
                        val wait = (m?.groupValues?.get(1)?.toLongOrNull() ?: 2L).coerceAtMost(20L)
                        Thread.sleep(wait * 1000)
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "HTTP ${resp.code}: ${resp.body?.string()}"); callback.error(); return
                    }
                    val src = resp.body!!.source()
                    while (!stopped) {
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val json = line.substring(5).trim()
                        if (json.isEmpty()) continue
                        val parts = JSONObject(json).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                        for (i in 0 until parts.length()) {
                            val d = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data") ?: continue
                            if (d.isEmpty()) continue
                            val pcm = Base64.decode(d, Base64.DEFAULT)
                            if (!started) {
                                Log.d(TAG, "first audio after ${System.currentTimeMillis() - t0} ms")
                                callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                                started = true
                            }
                            all.write(pcm)
                            feed(pcm, callback)
                        }
                    }
                    if (started) {
                        if (!stopped) cache.put(cacheKey, all.toByteArray())
                        callback.done(); return
                    }
                }
            }
            callback.error()
        } catch (e: Exception) {
            Log.e(TAG, "synthesis failed", e)
            if (started) callback.done() else callback.error()
        }
    }

    private fun play(pcm: ByteArray, callback: SynthesisCallback) {
        callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
        feed(pcm, callback)
        callback.done()
    }

    private fun feed(pcm: ByteArray, callback: SynthesisCallback) {
        val max = callback.maxBufferSize
        var off = 0
        while (off < pcm.size && !stopped) {
            val len = minOf(max, pcm.size - off)
            callback.audioAvailable(pcm, off, len)
            off += len
        }
    }
}

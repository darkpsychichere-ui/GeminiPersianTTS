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
        .build()

    @Volatile private var stopped = false

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

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Log.e(TAG, "HTTP ${resp.code}: $raw"); callback.error(); return }
                val data = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getJSONObject("inlineData").getString("data")
                val pcm = Base64.decode(data, Base64.DEFAULT)
                callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                val max = callback.maxBufferSize
                var off = 0
                while (off < pcm.size && !stopped) {
                    val len = minOf(max, pcm.size - off)
                    callback.audioAvailable(pcm, off, len)
                    off += len
                }
                callback.done()
            }
        } catch (e: Exception) {
            Log.e(TAG, "synthesis failed", e)
            callback.error()
        }
    }
}

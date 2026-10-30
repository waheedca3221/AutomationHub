package com.masterhub.sheeda

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale

object SheedaTextSplitter {
    /**
     * Splits text into natural sentence-by-sentence chunks for low-latency streaming playback.
     */
    fun splitIntoSentences(text: String): List<String> {
        val raw = text.split(Regex("(?<=[.!?\\n])\\s+"))
        return raw.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

class SheedaAudioPlayer(
    private val context: Context,
    private val client: JarvisTailnetClient,
    private val onSpeechStateChanged: (Boolean) -> Unit,
    private val onSentenceSpoken: (String) -> Unit
) {
    companion object {
        private const val TAG = "SheedaAudio"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var onDeviceTts: TextToSpeech? = null
    private var isTtsInitialized = false

    private val sentenceQueue = ArrayDeque<String>()
    var isSpeaking = false
        private set

    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                try {
                    mediaPlayer?.pause()
                } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    mediaPlayer?.setVolume(0.2f, 0.2f)
                } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    mediaPlayer?.setVolume(1.0f, 1.0f)
                    mediaPlayer?.start()
                } catch (_: Exception) {}
            }
        }
    }

    init {
        initTtsEngine()
    }

    private fun initTtsEngine() {
        val prefs = context.getSharedPreferences("sheeda_config", Context.MODE_PRIVATE)
        val savedEngine = prefs.getString("tts_engine", null)

        var targetEngine: String? = savedEngine
        if (targetEngine.isNullOrBlank()) {
            try {
                val tempTts = TextToSpeech(context, null)
                val engines = tempTts.engines
                for (eng in engines) {
                    val nameLower = eng.name.lowercase()
                    if (nameLower.contains("sherpa") || nameLower.contains("kokoro") || nameLower.contains("k2fsa")) {
                        targetEngine = eng.name
                        Log.i(TAG, "Preferred FOSS Sherpa-ONNX/Kokoro TTS engine found: ${eng.name}")
                        break
                    }
                }
                tempTts.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "Engine probe exception: $e")
            }
        }

        val initListener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsInitialized = true
                configureBestTtsVoice(savedVoice = prefs.getString("tts_voice", null))
            } else {
                Log.w(TAG, "On-device TTS init failed with status $status")
            }
        }

        onDeviceTts = if (!targetEngine.isNullOrBlank()) {
            TextToSpeech(context, initListener, targetEngine)
        } else {
            TextToSpeech(context, initListener)
        }

        onDeviceTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                handler.post { onSpeechStateChanged(true) }
            }

            override fun onDone(utteranceId: String?) {
                handler.post { playNextSentence() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { playNextSentence() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handler.post { playNextSentence() }
            }
        })
    }

    private fun configureBestTtsVoice(savedVoice: String?) {
        val tts = onDeviceTts ?: return
        try {
            val voices = tts.voices
            if (!voices.isNullOrEmpty()) {
                if (!savedVoice.isNullOrBlank()) {
                    val matching = voices.firstOrNull { it.name == savedVoice }
                    if (matching != null) {
                        tts.voice = matching
                        Log.i(TAG, "Applied user-configured TTS voice: ${matching.name}")
                        return
                    }
                }

                // Pick the most natural installed voice automatically
                val englishVoices = voices.filter { it.locale.language.startsWith("en") }
                val bestVoice = (if (englishVoices.isNotEmpty()) englishVoices else voices).maxByOrNull { v ->
                    var score = 0
                    if (v.quality >= Voice.QUALITY_VERY_HIGH) score += 30
                    else if (v.quality >= Voice.QUALITY_HIGH) score += 20
                    else if (v.quality >= Voice.QUALITY_NORMAL) score += 10

                    if (!v.isNetworkConnectionRequired) score += 25

                    val n = v.name.lowercase()
                    if (n.contains("natural") || n.contains("neural") || n.contains("studio")) score += 25
                    if (n.contains("en-us")) score += 10
                    score
                }

                if (bestVoice != null) {
                    tts.voice = bestVoice
                    Log.i(TAG, "Auto-selected natural voice: ${bestVoice.name}")
                }
            } else {
                tts.language = Locale.US
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voice config error: $e")
            tts.language = Locale.US
        }
    }

    fun speakText(
        text: String,
        isUserTalk: Boolean = false,
        isQuietHours: Boolean = false
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        // Proactive reports muted during quiet hours; Talk is always answered aloud
        if (!isUserTalk && isQuietHours) {
            Log.i(TAG, "Proactive speech muted due to quiet hours.")
            return
        }

        // Respect phone silent mode: silent/vibrate suppresses proactive audio
        val ringerMode = audioManager.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT && !isUserTalk) {
            Log.i(TAG, "Phone in silent mode; skipping proactive speech.")
            return
        }

        // Barge-in: cancel any currently playing audio immediately
        stop()

        val sentences = SheedaTextSplitter.splitIntoSentences(clean)
        if (sentences.isEmpty()) return

        sentenceQueue.addAll(sentences)
        requestAudioFocus()
        playNextSentence()
    }

    private fun playNextSentence() {
        if (sentenceQueue.isEmpty()) {
            isSpeaking = false
            abandonAudioFocus()
            onSpeechStateChanged(false)
            return
        }

        val sentence = sentenceQueue.poll() ?: return
        isSpeaking = true
        onSpeechStateChanged(true)
        onSentenceSpoken(sentence)

        val ttsUrl = client.getTtsUrl(sentence)
        playFromUrlWithFallback(ttsUrl, sentence)
    }

    private fun playFromUrlWithFallback(url: String, sentence: String) {
        try {
            releaseMediaPlayer()
            mediaPlayer = MediaPlayer().apply {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(audioAttributes)
                setDataSource(context, Uri.parse(url))

                setOnPreparedListener { mp ->
                    try {
                        mp.start()
                    } catch (e: Exception) {
                        fallbackToOnDeviceTts(sentence)
                    }
                }

                setOnCompletionListener {
                    playNextSentence()
                }

                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer streaming failed ($what, $extra); falling back to on-device TTS.")
                    fallbackToOnDeviceTts(sentence)
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer preparation error: $e. Falling back to on-device TTS.")
            fallbackToOnDeviceTts(sentence)
        }
    }

    private fun fallbackToOnDeviceTts(sentence: String) {
        releaseMediaPlayer()
        val tts = onDeviceTts
        if (tts != null && isTtsInitialized) {
            val utteranceId = "sheeda_utt_${System.currentTimeMillis()}"
            val result = tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "On-device TTS speak returned error: $result")
                handler.post { playNextSentence() }
            }
        } else {
            Log.w(TAG, "On-device TTS not ready; skipping sentence audio.")
            handler.post { playNextSentence() }
        }
    }

    /**
     * Immediately stops playback, clears speech queue, releases audio focus, and resets state.
     */
    fun stop() {
        sentenceQueue.clear()
        isSpeaking = false
        releaseMediaPlayer()
        try {
            onDeviceTts?.stop()
        } catch (_: Exception) {}
        abandonAudioFocus()
        onSpeechStateChanged(false)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

            focusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun release() {
        stop()
        try {
            onDeviceTts?.shutdown()
        } catch (_: Exception) {}
        onDeviceTts = null
        isTtsInitialized = false
    }
}

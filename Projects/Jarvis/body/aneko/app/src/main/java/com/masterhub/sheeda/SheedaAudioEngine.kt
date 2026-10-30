package com.masterhub.sheeda

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.Locale

/**
 * SheedaAudioEngine
 *
 * Handles audio playback for Sheeda:
 * - Sentence-by-sentence queue for sub-second initial speech onset.
 * - PC audio streaming from Jarvis Sovereign Core (/api/tts?fmt=mp3 or /api/speak).
 * - Audio focus & ducking (pauses/ducks music, resumes after).
 * - Silent mode observance (proactive reports stay silent; user Talk always speaks aloud).
 * - On-device TextToSpeech fallback (Sherpa-ONNX Kokoro voice -> Google TTS).
 * - Barge-in on tap (instantly terminates active playback and releases focus).
 */
class SheedaAudioEngine(
    private val context: Context,
    private val client: JarvisTailnetClient
) {
    companion object {
        private const val TAG = "SheedaAudio"
        private const val SHERPA_PACKAGE = "com.k2fsa.sherpa.onnx.tts"
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineScope = CoroutineScope(Dispatchers.Main)

    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Sentence queue
    private val sentenceQueue = ArrayDeque<String>()
    var isSpeaking: Boolean = false
        private set
    private var isTalkResponse: Boolean = false

    private var onStartedCallback: (() -> Unit)? = null
    private var onEndedCallback: (() -> Unit)? = null

    // On-device TTS Fallback
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var activeEngineName: String = "auto"

    init {
        initOnDeviceTts()
    }

    private fun initOnDeviceTts() {
        val prefs = context.getSharedPreferences("sheeda_config", Context.MODE_PRIVATE)
        val preferredEngine = prefs.getString("tts_engine", null)

        val initListener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                configureBestVoice()
                Log.i(TAG, "On-device TTS initialized successfully ($activeEngineName)")
            } else {
                ttsReady = false
                Log.w(TAG, "On-device TTS initialization failed with status $status")
            }
        }

        try {
            if (preferredEngine != null) {
                textToSpeech = TextToSpeech(context, initListener, preferredEngine)
                activeEngineName = preferredEngine
            } else {
                // Try Sherpa-ONNX first, then default
                textToSpeech = TextToSpeech(context, initListener)
                activeEngineName = textToSpeech?.defaultEngine ?: "system"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating TextToSpeech: ${e.message}")
        }
    }

    private fun configureBestVoice() {
        val tts = textToSpeech ?: return
        try {
            val voices = tts.voices
            if (!voices.isNullOrEmpty()) {
                // Priority 1: Sherpa-ONNX Kokoro voice
                val kokoroVoice = voices.firstOrNull { it.name.contains("kokoro", ignoreCase = true) }
                // Priority 2: High quality English non-network natural/neural voice
                val naturalVoice = voices.firstOrNull { v ->
                    v.locale.language == "en" && !v.isNetworkConnectionRequired &&
                            (v.name.contains("natural", ignoreCase = true) || v.name.contains("neural", ignoreCase = true))
                }
                // Priority 3: Any local English voice
                val englishVoice = voices.firstOrNull { v ->
                    v.locale.language == "en" && !v.isNetworkConnectionRequired
                }

                val selected = kokoroVoice ?: naturalVoice ?: englishVoice
                if (selected != null) {
                    tts.voice = selected
                    Log.i(TAG, "Selected TTS voice: ${selected.name} (${selected.locale})")
                }
            } else {
                tts.language = Locale.US
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error configuring voice: ${e.message}")
            try {
                tts.language = Locale.US
            } catch (_: Exception) {}
        }
    }

    /**
     * Splits text into natural sentence units for fast streaming playback.
     */
    fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        // Strip markdown formatting symbols for clean acoustic speech
        val cleaned = text
            .replace(Regex("""[*_#`\[\]]"""), "")
            .replace(Regex("""http\S+"""), "link")
            .trim()

        val rawSentences = cleaned.split(Regex("""(?<=[.!?\n])\s+"""))
        return rawSentences
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != "!" && it != "?" }
    }

    /**
     * Queue speech and begin sentence-by-sentence playback.
     */
    fun speak(
        text: String,
        isTalkResponse: Boolean = false,
        onSpeechStarted: () -> Unit = {},
        onSpeechEnded: () -> Unit = {}
    ) {
        stopSpeech()

        this.isTalkResponse = isTalkResponse
        this.onStartedCallback = onSpeechStarted
        this.onEndedCallback = onSpeechEnded

        val sentences = splitIntoSentences(text)
        if (sentences.isEmpty()) {
            onSpeechEnded()
            return
        }

        // Check silent mode:
        val ringer = audioManager.ringerMode
        val isSilent = ringer == AudioManager.RINGER_MODE_SILENT || ringer == AudioManager.RINGER_MODE_VIBRATE
        if (isSilent && !isTalkResponse) {
            // Respect phone silent mode for proactive ambient speech; only user Talk speaks aloud
            Log.i(TAG, "Silent mode active; suppressing proactive speech aloud")
            onSpeechEnded()
            return
        }

        sentenceQueue.clear()
        sentenceQueue.addAll(sentences)

        isSpeaking = true
        onStartedCallback?.invoke()

        playNextSentence()
    }

    private fun playNextSentence() {
        if (sentenceQueue.isEmpty()) {
            finishSpeech()
            return
        }

        val sentence = sentenceQueue.removeFirst()
        requestAudioFocus()

        engineScope.launch {
            val audioBytes = fetchPcAudio(sentence)
            if (audioBytes != null && audioBytes.isNotEmpty()) {
                playBytesWithMediaPlayer(audioBytes, sentence)
            } else {
                Log.w(TAG, "PC TTS unavailable, falling back to on-device TTS")
                playWithOnDeviceTts(sentence)
            }
        }
    }

    private suspend fun fetchPcAudio(sentence: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(sentence, "UTF-8")
            val url = "${client.baseServerUrl}/api/tts?text=$encoded&fmt=mp3"
            client.downloadAudio(url).getOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching PC audio: ${e.message}")
            null
        }
    }

    private fun playBytesWithMediaPlayer(bytes: ByteArray, sentenceFallback: String) {
        try {
            val tempFile = File(context.cacheDir, "sheeda_chunk_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempFile).use { it.write(bytes) }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(attributes)
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener { start() }
                setOnCompletionListener {
                    tempFile.delete()
                    release()
                    mediaPlayer = null
                    playNextSentence()
                }
                setOnErrorListener { _, _, _ ->
                    tempFile.delete()
                    release()
                    mediaPlayer = null
                    playWithOnDeviceTts(sentenceFallback)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer setup error: ${e.message}")
            playWithOnDeviceTts(sentenceFallback)
        }
    }

    private fun playWithOnDeviceTts(sentence: String) {
        val tts = textToSpeech
        if (tts != null && ttsReady) {
            val utteranceId = "sheeda_utterance_${System.currentTimeMillis()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    mainHandler.post { playNextSentence() }
                }
                override fun onError(id: String?) {
                    mainHandler.post { playNextSentence() }
                }
            })

            val params = android.os.Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            // If on-device TTS is unready, skip to next sentence
            mainHandler.postDelayed({ playNextSentence() }, 500)
        }
    }

    /**
     * Stop active playback immediately (barge-in or dismissal).
     */
    fun stopSpeech() {
        sentenceQueue.clear()

        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaPlayer: ${e.message}")
        }

        try {
            textToSpeech?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS: ${e.message}")
        }

        releaseAudioFocus()

        if (isSpeaking) {
            isSpeaking = false
            onEndedCallback?.invoke()
        }
    }

    private fun finishSpeech() {
        releaseAudioFocus()
        isSpeaking = false
        onEndedCallback?.invoke()
    }

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        // Pause or duck if other critical audio enters
                    }
                }
                .build()
            audioFocusRequest = request
            return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            return audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun destroy() {
        stopSpeech()
        try {
            textToSpeech?.shutdown()
            textToSpeech = null
        } catch (_: Exception) {}
    }
}

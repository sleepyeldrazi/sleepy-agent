package com.sleepy.agent.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TtsState { INITIALIZING, READY, ERROR }

class TtsService(
    private val context: Context
) {
    private var textToSpeech: TextToSpeech? = null
    private val _state = MutableStateFlow(TtsState.INITIALIZING)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var isInitialized = false
    private var pendingCompletionCallback: (() -> Unit)? = null

    /**
     * Initializes the TTS engine and emits state changes.
     * Emits: INITIALIZING → READY (or ERROR if TTS not available)
     */
    fun initialize(): Flow<TtsState> {
        if (isInitialized && textToSpeech != null) {
            _state.value = TtsState.READY
            return state
        }

        _state.value = TtsState.INITIALIZING

        textToSpeech = TextToSpeech(context) { status ->
            when (status) {
                TextToSpeech.SUCCESS -> {
                    textToSpeech?.let { tts ->
                        // Set up progress listener for completion callbacks
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                // Speech started
                            }

                            override fun onDone(utteranceId: String?) {
                                pendingCompletionCallback?.invoke()
                                pendingCompletionCallback = null
                            }

                            override fun onError(utteranceId: String?) {
                                pendingCompletionCallback?.invoke()
                                pendingCompletionCallback = null
                            }
                        })

                        // Set default language
                        val result = tts.setLanguage(Locale.getDefault())
                        if (result == TextToSpeech.LANG_MISSING_DATA ||
                            result == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            // Language not available, but TTS is still functional
                            // Fallback to US English
                            tts.setLanguage(Locale.US)
                        }

                        isInitialized = true
                        _state.value = TtsState.READY
                    }
                }
                TextToSpeech.ERROR -> {
                    _state.value = TtsState.ERROR
                    // TTS engine not installed - redirect to install
                    redirectToTtsInstall()
                }
            }
        }

        return state
    }

    /**
     * Speaks the given text. Optional callback invoked when speech completes.
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized || textToSpeech == null) {
            onComplete?.invoke()
            return
        }

        pendingCompletionCallback = onComplete

        // Stop any current speech
        stop()

        val utteranceId = System.currentTimeMillis().toString()
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Stops the current speech.
     */
    fun stop() {
        textToSpeech?.stop()
    }

    /**
     * Returns true if currently speaking.
     */
    fun isSpeaking(): Boolean {
        return textToSpeech?.isSpeaking ?: false
    }

    /**
     * Shuts down the TTS engine and releases resources.
     */
    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
        pendingCompletionCallback = null
        _state.value = TtsState.INITIALIZING
    }

    /**
     * Redirects user to install TTS engine from Play Store.
     */
    private fun redirectToTtsInstall() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=com.google.android.tts")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Play Store not available, open in browser
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}

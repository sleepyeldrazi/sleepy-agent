package com.sleepy.agent.audio

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Simple Voice Activity Detector that monitors audio levels and triggers
 * callbacks when speech starts and stops.
 */
class VoiceActivityDetector(
    private val silenceThresholdMs: Long = 2000,  // Stop after 2 seconds of silence
    private val speechThresholdDb: Double = -40.0,  // Consider speech above -40dB
    private val silenceThresholdDb: Double = -50.0  // Consider silence below -50dB
) {
    companion object {
        private const val TAG = "VAD"
        private const val MIN_SPEECH_DURATION_MS = 500  // Minimum speech before we start monitoring for silence
    }

    private val isRunning = AtomicBoolean(false)
    private val lastSpeechTime = AtomicLong(0)
    private val speechStartTime = AtomicLong(0)
    private val hasDetectedSpeech = AtomicBoolean(false)
    
    private var onSpeechStart: (() -> Unit)? = null
    private var onSpeechEnd: (() -> Unit)? = null
    private var onAudioLevel: ((Double) -> Unit)? = null
    
    private var monitoringJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun setCallbacks(
        onSpeechStart: (() -> Unit)? = null,
        onSpeechEnd: (() -> Unit)? = null,
        onAudioLevel: ((Double) -> Unit)? = null
    ) {
        this.onSpeechStart = onSpeechStart
        this.onSpeechEnd = onSpeechEnd
        this.onAudioLevel = onAudioLevel
    }

    fun start() {
        isRunning.set(true)
        hasDetectedSpeech.set(false)
        lastSpeechTime.set(System.currentTimeMillis())
        speechStartTime.set(0)
        
        // Start monitoring job
        monitoringJob = scope.launch {
            while (isRunning.get()) {
                checkForSilenceTimeout()
                delay(100)
            }
        }
        
        Log.d(TAG, "VAD started")
    }

    fun stop() {
        isRunning.set(false)
        monitoringJob?.cancel()
        monitoringJob = null
        hasDetectedSpeech.set(false)
        Log.d(TAG, "VAD stopped")
    }

    /**
     * Process audio buffer for voice activity detection.
     * Call this with each audio chunk received.
     */
    fun processAudio(buffer: ByteArray, audioLevelDb: Double) {
        if (!isRunning.get()) return
        
        onAudioLevel?.invoke(audioLevelDb)
        
        val now = System.currentTimeMillis()
        
        if (audioLevelDb > speechThresholdDb) {
            // Speech detected
            if (!hasDetectedSpeech.get()) {
                // First speech detection
                hasDetectedSpeech.set(true)
                speechStartTime.set(now)
                lastSpeechTime.set(now)
                onSpeechStart?.invoke()
                Log.d(TAG, "Speech started")
            } else {
                // Ongoing speech
                lastSpeechTime.set(now)
            }
        }
    }
    
    private suspend fun checkForSilenceTimeout() {
        if (!hasDetectedSpeech.get()) return
        
        val now = System.currentTimeMillis()
        val speechStart = speechStartTime.get()
        val lastSpeech = lastSpeechTime.get()
        val speechDuration = now - speechStart
        val silenceDuration = now - lastSpeech
        
        // Only check for silence after minimum speech duration
        if (speechDuration > MIN_SPEECH_DURATION_MS && silenceDuration > silenceThresholdMs) {
            Log.d(TAG, "Silence detected for ${silenceDuration}ms, triggering speech end")
            onSpeechEnd?.invoke()
            // Reset to prevent multiple triggers
            hasDetectedSpeech.set(false)
        }
    }

    fun isActive(): Boolean = isRunning.get()
    fun hasDetectedSpeech(): Boolean = hasDetectedSpeech.get()
}

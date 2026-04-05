package com.sleepy.agent.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

interface AudioRecorder {
    fun startRecording(): Result<Unit>
    suspend fun stopRecording(): ByteArray
    fun isRecording(): Boolean
    fun setOnAudioChunkListener(listener: (ByteArray) -> Unit)
    fun setOnSilenceDetectedListener(listener: (() -> Unit)?)
}

class AudioRecorderImpl(
    private val context: Context
) : AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2 // 16-bit = 2 bytes
        private const val CHUNK_DURATION_MS = 1000 // 1 second chunks
        private const val SILENCE_TIMEOUT_MS = 2000L // Stop after 2 seconds of silence
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val isRecordingState = AtomicBoolean(false)
    private val recordedData = ByteArrayOutputStream()
    private var audioChunkListener: ((ByteArray) -> Unit)? = null
    private var silenceListener: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // VAD for auto-stopping on silence
    private val vad = VoiceActivityDetector(
        silenceThresholdMs = SILENCE_TIMEOUT_MS,
        speechThresholdDb = -40.0,
        silenceThresholdDb = -50.0
    )

    private val bufferSize: Int by lazy {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val oneSecondBufferSize = SAMPLE_RATE * BYTES_PER_SAMPLE
        maxOf(minBufferSize, oneSecondBufferSize)
    }

    private val chunkSize: Int
        get() = SAMPLE_RATE * BYTES_PER_SAMPLE // 1 second = 32000 bytes

    override fun setOnAudioChunkListener(listener: (ByteArray) -> Unit) {
        audioChunkListener = listener
    }
    
    override fun setOnSilenceDetectedListener(listener: (() -> Unit)?) {
        silenceListener = listener
        vad.setCallbacks(
            onSpeechStart = null,
            onSpeechEnd = listener,
            onAudioLevel = null
        )
    }

    override fun startRecording(): Result<Unit> {
        if (isRecordingState.get()) {
            return Result.failure(IllegalStateException("Already recording"))
        }

        if (!hasRecordAudioPermission()) {
            return Result.failure(SecurityException("RECORD_AUDIO permission not granted"))
        }

        return try {
            initializeAudioRecord()
            startRecordingInternal()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            Result.failure(e)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun initializeAudioRecord() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }
    }

    private fun startRecordingInternal() {
        audioRecord?.startRecording()
        isRecordingState.set(true)
        recordedData.reset()
        
        // Start VAD
        vad.setCallbacks(
            onSpeechStart = { Log.d(TAG, "VAD: Speech detected") },
            onSpeechEnd = { 
                Log.d(TAG, "VAD: Silence detected, auto-stopping")
                silenceListener?.invoke()
            },
            onAudioLevel = { db ->
                // Log audio level for debugging
                // Log.v(TAG, "Audio level: ${db.toInt()} dB")
            }
        )
        vad.start()

        recordingJob = scope.launch {
            recordAudioChunks()
        }

        Log.d(TAG, "Recording started with VAD (sampleRate=$SAMPLE_RATE, bufferSize=$bufferSize)")
    }

    private suspend fun recordAudioChunks() {
        val audioRecord = this.audioRecord ?: return
        val buffer = ShortArray(chunkSize / BYTES_PER_SAMPLE)
        val job = recordingJob ?: return

        while (isRecordingState.get() && job.isActive) {
            val bytesRead = audioRecord.read(buffer, 0, buffer.size)

            if (bytesRead > 0) {
                val byteBuffer = ByteBuffer.allocate(bytesRead * BYTES_PER_SAMPLE)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                buffer.take(bytesRead).forEach { short ->
                    byteBuffer.putShort(short)
                }
                val chunkBytes = byteBuffer.array()

                synchronized(recordedData) {
                    recordedData.write(chunkBytes)
                }
                
                // Process for VAD
                val audioLevel = calculateAudioLevelDb(chunkBytes)
                vad.processAudio(chunkBytes, audioLevel)

                audioChunkListener?.invoke(chunkBytes)
            } else if (bytesRead < 0) {
                Log.e(TAG, "AudioRecord read error: $bytesRead")
                break
            }
        }
    }

    override suspend fun stopRecording(): ByteArray {
        if (!isRecordingState.get()) {
            Log.w(TAG, "stopRecording called but not recording")
            return byteArrayOf()
        }

        Log.d(TAG, "Stopping recording...")
        
        // Cancel job first, then stop state
        recordingJob?.cancel()
        
        withContext(Dispatchers.IO) {
            recordingJob?.join()
        }
        
        isRecordingState.set(false)
        
        // Stop VAD
        vad.stop()

        audioRecord?.let { record ->
            try {
                record.stop()
                Log.d(TAG, "AudioRecord stopped successfully")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord stop failed (may not have started)", e)
            }
        }

        val result = synchronized(recordedData) {
            recordedData.toByteArray()
        }

        cleanup()

        // Validate the audio data
        if (result.isEmpty()) {
            Log.w(TAG, "No audio data recorded")
            return byteArrayOf()
        }
        
        if (result.size < 6400) { // Less than 200ms at 16kHz 16-bit
            Log.w(TAG, "Audio too short: ${result.size} bytes (${result.size / 32}ms)")
            return byteArrayOf()
        }

        Log.d(TAG, "Recording stopped, captured ${result.size} bytes (${result.size / 32}ms of audio)")
        return result
    }

    private fun cleanup() {
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.release()
        audioRecord = null
        
        vad.stop()

        recordedData.reset()
        isRecordingState.set(false)
    }
    
    /**
     * Calculate audio level in dB from PCM16 buffer.
     */
    private fun calculateAudioLevelDb(buffer: ByteArray): Double {
        if (buffer.size < 2) return -100.0
        
        val byteBuffer = ByteBuffer.wrap(buffer)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        
        var sum = 0.0
        var count = 0
        
        while (byteBuffer.remaining() >= 2) {
            val sample = byteBuffer.short.toInt()
            sum += sample * sample
            count++
        }
        
        if (count == 0) return -100.0
        
        val rms = kotlin.math.sqrt(sum / count)
        return if (rms > 0) {
            20 * kotlin.math.log10(rms / Short.MAX_VALUE)
        } else {
            -100.0
        }
    }

    override fun isRecording(): Boolean {
        return isRecordingState.get()
    }

    fun getRecordingDurationMs(): Long {
        val byteCount = synchronized(recordedData) {
            recordedData.size()
        }
        return (byteCount / BYTES_PER_SAMPLE * 1000L) / SAMPLE_RATE
    }

    fun getAudioLevel(buffer: ByteArray): Double {
        if (buffer.isEmpty()) return 0.0

        val byteBuffer = ByteBuffer.wrap(buffer)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

        var sum = 0.0
        var count = 0

        while (byteBuffer.remaining() >= 2) {
            val sample = byteBuffer.short.toInt()
            sum += sample * sample
            count++
        }

        if (count == 0) return 0.0

        val rms = kotlin.math.sqrt(sum / count)
        return 20 * kotlin.math.log10(rms / Short.MAX_VALUE)
    }
}

class AudioPermissionException(message: String) : SecurityException(message)

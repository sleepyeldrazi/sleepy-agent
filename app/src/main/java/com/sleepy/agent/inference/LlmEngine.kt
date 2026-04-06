package com.sleepy.agent.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.*
import com.sleepy.agent.audio.WavConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * LLM Engine interface for text generation with optional multimodal inputs.
 */
interface LlmEngine {
    suspend fun loadModel(modelPath: String): Result<Unit>
    
    /**
     * Creates a new conversation with the given system prompt.
     * This should be called once per chat session to enable KV cache reuse.
     */
    fun createConversation(systemPrompt: String): Conversation
    
    /**
     * Generate a response within an existing conversation.
     * This reuses the KV cache from previous turns.
     */
    suspend fun generate(
        conversation: Conversation,
        prompt: String,
        audioData: ByteArray? = null,
        images: List<Bitmap>? = null
    ): String

    suspend fun generateStream(
        conversation: Conversation,
        prompt: String,
        audioData: ByteArray? = null,
        images: List<Bitmap>? = null,
        onToken: (String) -> Unit
    )

    fun isLoaded(): Boolean
    fun unload()
}

/**
 * Wrapper for LiteRT-LM Conversation to manage lifecycle.
 */
class Conversation(
    internal val liteRtConversation: com.google.ai.edge.litertlm.Conversation
) : AutoCloseable {
    private var isClosed = false
    
    val isAlive: Boolean get() = !isClosed && liteRtConversation.isAlive
    
    override fun close() {
        if (!isClosed) {
            isClosed = true
            liteRtConversation.close()
        }
    }
}

/**
 * LiteRT-LM based LLM Engine implementation for Gemma 4 E2B/E4B models.
 * 
 * Uses .litertlm model format - download from HuggingFace LiteRT Community:
 * https://huggingface.co/litert-community
 * 
 * Gemma 4 E2B is natively multimodal - it can process text, images, and audio directly.
 */
class LiteRtLlmEngine(
    private val context: Context
) : LlmEngine {

    companion object {
        private const val TAG = "LiteRtLlmEngine"
        private const val MAX_TOKENS = 16384  // 16k context as requested
    }

    private var engine: Engine? = null
    private var currentModelPath: String? = null
    private val lock = Any()

    override suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            synchronized(lock) {
                unload()
                
                val modelFile = File(modelPath)
                if (!modelFile.exists()) {
                    throw IllegalArgumentException("Model file not found: $modelPath")
                }

                Log.d(TAG, "Loading model from: $modelPath")
                
                // Ensure cache directory exists
                val cacheDir = File(context.cacheDir, "litertlm_cache")
                cacheDir.mkdirs()
                Log.d(TAG, "Using cache dir: ${cacheDir.absolutePath}")

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),  // Required for image processing!
                    audioBackend = Backend.CPU(),   // Required for audio processing!
                    maxNumTokens = MAX_TOKENS,
                    cacheDir = cacheDir.absolutePath
                )

                val newEngine = Engine(engineConfig)
                newEngine.initialize()
                engine = newEngine
                currentModelPath = modelPath

                Log.d(TAG, "Model loaded successfully with 16k context")
                Unit
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to load model", e)
        }
    }

    override fun createConversation(systemPrompt: String): Conversation {
        val eng = synchronized(lock) { engine } ?: throw IllegalStateException("Model not loaded")
        
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt)
        )
        
        val liteRtConversation = eng.createConversation(conversationConfig)
        return Conversation(liteRtConversation)
    }

    override suspend fun generate(
        conversation: Conversation,
        prompt: String,
        audioData: ByteArray?,
        images: List<Bitmap>?
    ): String = withContext(Dispatchers.Default) {
        try {
            if (!conversation.isAlive) {
                Log.e(TAG, "Conversation is closed")
                return@withContext "Error: Conversation closed"
            }

            val contents = buildContents(prompt, audioData, images)
            val response = conversation.liteRtConversation.sendMessage(contents)
            response.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            "Error: ${e.message}"
        }
    }

    override suspend fun generateStream(
        conversation: Conversation,
        prompt: String,
        audioData: ByteArray?,
        images: List<Bitmap>?,
        onToken: (String) -> Unit
    ) {
        withContext(Dispatchers.Default) {
            try {
                if (!conversation.isAlive) {
                    Log.e(TAG, "Conversation is closed or not alive")
                    onToken("Error: Conversation closed")
                    return@withContext
                }

                // For multimodal inputs, we need to use the Contents API
                if (audioData != null || !images.isNullOrEmpty()) {
                    Log.d(TAG, "Processing multimodal input (audio=${audioData?.size ?: 0} bytes, images=${images?.size ?: 0})")
                    
                    try {
                        val contents = buildContents(prompt, audioData, images)
                        
                        if (contents.contents.isEmpty()) {
                            Log.w(TAG, "No valid content to send")
                            onToken("Error: No valid audio or image data")
                            return@withContext
                        }
                        
                        Log.d(TAG, "Built contents with ${contents.contents.size} parts, sending to model...")
                        
                        val response = conversation.liteRtConversation.sendMessage(contents)
                        Log.d(TAG, "Got response: ${response.toString().take(100)}...")
                        
                        onToken(response.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "Multimodal processing failed", e)
                        onToken("Error processing input: ${e.message}")
                    }
                } else {
                    // Text-only streaming - reuses KV cache!
                    Log.d(TAG, "Starting text-only streaming with prompt: ${prompt.take(100)}...")
                    conversation.liteRtConversation.sendMessageAsync(prompt)
                        .catch { e -> Log.e(TAG, "Stream error", e) }
                        .collect { message ->
                            Log.d(TAG, "Token received: ${message.toString().take(30)}...")
                            onToken(message.toString())
                        }
                    Log.d(TAG, "Text streaming completed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming generation failed", e)
                onToken("Error: ${e.message}")
            }
        }
    }
    
    /**
     * Builds a Contents object with text, audio, and/or images.
     */
    private fun buildContents(
        prompt: String,
        audioData: ByteArray?,
        images: List<Bitmap>?
    ): Contents {
        val contentList = mutableListOf<Content>()
        
        // Add images first if provided
        images?.take(1)?.forEachIndexed { index, bitmap ->
            try {
                Log.d(TAG, "Processing image $index: ${bitmap.width}x${bitmap.height}")
                
                if (bitmap.isRecycled) {
                    Log.e(TAG, "Bitmap is recycled!")
                    return@forEachIndexed
                }
                
                // Resize to max 512x512
                val resized = resizeBitmap(bitmap, 512, 512)
                
                val stream = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val bytes = stream.toByteArray()
                
                if (bytes.isNotEmpty()) {
                    contentList.add(Content.ImageBytes(bytes))
                    Log.d(TAG, "Added image: ${bytes.size} bytes")
                }
                
                if (resized !== bitmap) resized.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process image", e)
            }
        }
        
        // Add audio if provided
        audioData?.let { audio ->
            try {
                if (audio.size >= 6400) {
                    val wavData = if (WavConverter.isWav(audio)) audio else WavConverter.pcmToWav(audio)
                    if (wavData.isNotEmpty()) {
                        contentList.add(Content.AudioBytes(wavData))
                        Log.d(TAG, "Added audio: ${wavData.size} bytes")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add audio", e)
            }
        }
        
        contentList.add(Content.Text(prompt))
        return Contents.of(contentList)
    }
    
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxWidth && height <= maxHeight) return bitmap
        
        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    override fun isLoaded(): Boolean = synchronized(lock) {
        engine != null
    }

    override fun unload() {
        synchronized(lock) {
            engine?.close()
            engine = null
            currentModelPath = null
            Log.d(TAG, "Model unloaded")
        }
    }
}

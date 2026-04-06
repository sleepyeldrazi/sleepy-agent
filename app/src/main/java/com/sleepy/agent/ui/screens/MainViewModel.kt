package com.sleepy.agent.ui.screens

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepy.agent.audio.AudioRecorder
import com.sleepy.agent.audio.TtsService
import com.sleepy.agent.data.ConversationInfo
import com.sleepy.agent.data.ConversationStorage
import com.sleepy.agent.download.ModelDownloadManager
import com.sleepy.agent.inference.Agent
import com.sleepy.agent.inference.AgentEvent
import com.sleepy.agent.inference.LlmEngine
import com.sleepy.agent.settings.UserSettings
import com.sleepy.agent.tools.WebSearchTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class UIState {
    IDLE,
    LISTENING,
    PROCESSING,
    EXECUTING_TOOL,
    SPEAKING,
    ERROR
}

@Serializable
data class ConversationMessage(
    val id: String = System.currentTimeMillis().toString(),
    val text: String,
    val isUser: Boolean,
    val isToolCall: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class MainViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val context: Context,
    private val audioRecorder: AudioRecorder,
    private val ttsService: TtsService,
    private val agent: Agent,
    private val llmEngine: LlmEngine,
    private val userSettings: UserSettings,
    private val webSearchTool: WebSearchTool
) : ViewModel() {

    private val conversationStorage = ConversationStorage(context)
    
    private val _uiState = MutableStateFlow(UIState.IDLE)
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    private val _messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()
    
    private val _conversations = MutableStateFlow<List<ConversationInfo>>(emptyList())
    val conversations: StateFlow<List<ConversationInfo>> = _conversations.asStateFlow()
    
    private var currentConversationId: String = savedStateHandle.get<String>("conversation_id") 
        ?: conversationStorage.createNewConversationId()

    private var recordedAudio: ByteArray = byteArrayOf()
    
    // Track if user started with voice or text for TTS auto mode
    private var firstInputWasVoice: Boolean? = null
    
    companion object {
        private const val TAG = "MainViewModel"
        private const val KEY_MESSAGES = "messages"
    }

    init {
        ttsService.initialize()
        restoreState()
        loadConversationsList()
        
        viewModelScope.launch {
            var modelPath = userSettings.modelPath.first()
            
            // Auto-detect downloaded model if path is empty
            if (modelPath.isEmpty()) {
                if (ModelDownloadManager.isE2BDownloaded(context)) {
                    modelPath = ModelDownloadManager.getE2BModelFile(context).absolutePath
                    userSettings.setModelPath(modelPath)
                    Log.d(TAG, "Auto-detected E2B model at: $modelPath")
                } else if (ModelDownloadManager.isE4BDownloaded(context)) {
                    modelPath = ModelDownloadManager.getE4BModelFile(context).absolutePath
                    userSettings.setModelPath(modelPath)
                    Log.d(TAG, "Auto-detected E4B model at: $modelPath")
                }
            }
            
            if (modelPath.isNotEmpty() && !llmEngine.isLoaded()) {
                loadModel(modelPath)
            }
        }
        
        // Update web search URL when settings change
        viewModelScope.launch {
            userSettings.searchServerUrl.collect { url ->
                webSearchTool.updateBaseUrl(url)
                Log.d(TAG, "Updated web search URL to: $url")
            }
        }
    }
    
    private fun restoreState() {
        // Try to load from persistent storage first
        conversationStorage.loadConversation(currentConversationId)?.let { messages ->
            _messages.value = messages
            Log.d(TAG, "Loaded ${messages.size} messages from storage")
            return
        }
        
        // Fallback to SavedStateHandle for rotation
        savedStateHandle.get<String>(KEY_MESSAGES)?.let { json ->
            try {
                val restored = Json.decodeFromString<List<ConversationMessage>>(json)
                _messages.value = restored
                Log.d(TAG, "Restored ${restored.size} messages from SavedState")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore messages", e)
            }
        }
    }
    
    private fun saveState() {
        // Save to persistent storage
        if (_messages.value.isNotEmpty()) {
            conversationStorage.saveConversation(currentConversationId, _messages.value)
        }
        
        // Also save to SavedStateHandle for rotation
        try {
            val json = Json.encodeToString(_messages.value)
            savedStateHandle[KEY_MESSAGES] = json
            savedStateHandle["conversation_id"] = currentConversationId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save messages", e)
        }
    }
    
    private fun loadConversationsList() {
        _conversations.value = conversationStorage.getAllConversations()
    }
    
    fun loadConversation(id: String) {
        conversationStorage.loadConversation(id)?.let { messages ->
            // Save current conversation before switching
            if (_messages.value.isNotEmpty()) {
                conversationStorage.saveConversation(currentConversationId, _messages.value)
            }
            
            currentConversationId = id
            _messages.value = messages
            agent.reset() // Reset agent for new conversation context
            savedStateHandle[KEY_MESSAGES] = Json.encodeToString(messages)
            savedStateHandle["conversation_id"] = id
        }
        loadConversationsList()
    }
    
    fun startNewConversation() {
        // Save current conversation
        if (_messages.value.isNotEmpty()) {
            conversationStorage.saveConversation(currentConversationId, _messages.value)
        }
        
        // Create new one
        currentConversationId = conversationStorage.createNewConversationId()
        _messages.value = emptyList()
        _responseText.value = ""
        agent.reset()
        firstInputWasVoice = null
        saveState()
        loadConversationsList()
    }
    
    fun deleteConversation(id: String) {
        conversationStorage.deleteConversation(id)
        if (id == currentConversationId) {
            startNewConversation()
        } else {
            loadConversationsList()
        }
    }

    private suspend fun loadModel(modelPath: String) {
        Log.d(TAG, "Auto-loading model from: $modelPath")
        llmEngine.loadModel(modelPath)
            .onSuccess { 
                Log.d(TAG, "Model loaded successfully")
                // Pre-warm KV cache with system prompt for faster first response
                agent.prewarmCache()
            }
            .onFailure { e -> Log.e(TAG, "Failed to load model", e) }
    }

    fun startRecording() {
        // Track that first input was voice for TTS auto mode
        if (firstInputWasVoice == null) {
            firstInputWasVoice = true
            Log.d(TAG, "First input was voice - TTS auto-enabled")
        }
        
        audioRecorder.setOnSilenceDetectedListener {
            Log.d(TAG, "Auto-stopping recording due to silence")
            stopRecording()
        }
        
        val result = audioRecorder.startRecording()
        result.fold(
            onSuccess = {
                recordedAudio = byteArrayOf()
                _uiState.value = UIState.LISTENING
                Log.d(TAG, "Started recording with auto-stop")
            },
            onFailure = { e ->
                Log.e(TAG, "Failed to start recording", e)
                _uiState.value = UIState.ERROR
                _responseText.value = "Error: ${e.message}"
            }
        )
    }

    fun stopRecording() {
        viewModelScope.launch {
            val audioData = audioRecorder.stopRecording()
            recordedAudio = audioData
            
            if (audioData.isEmpty()) {
                Log.w(TAG, "No audio recorded or recording too short")
                _uiState.value = UIState.IDLE
                _responseText.value = "Recording too short, please try again"
                return@launch
            }
            
            Log.d(TAG, "Audio recorded: ${audioData.size} bytes")

            _uiState.value = UIState.PROCESSING
            
            val useServer = userSettings.enableServerDelegation.first()
            
            if (useServer) {
                processAudioWithServer(audioData)
            } else {
                processAudioWithLocalModel(audioData)
            }
        }
    }

    private suspend fun processAudioWithLocalModel(audioData: ByteArray) {
        try {
            if (!llmEngine.isLoaded()) {
                val modelPath = userSettings.modelPath.first()
                if (modelPath.isNotEmpty()) {
                    _responseText.value = "Loading model..."
                    val result = llmEngine.loadModel(modelPath)
                    result.onFailure { e ->
                        _uiState.value = UIState.ERROR
                        _responseText.value = "Failed to load model: ${e.message}"
                        return@processAudioWithLocalModel
                    }
                    agent.prewarmCache()
                } else {
                    _uiState.value = UIState.ERROR
                    _responseText.value = "No model loaded. Please go to Settings and load a model."
                    return
                }
            }

            val userMessage = ConversationMessage(
                text = "🎤 [Voice message]",
                isUser = true
            )
            _messages.value = _messages.value + userMessage
            saveState()

            Log.d(TAG, "Processing audio: ${audioData.size} bytes")

            val responseBuilder = StringBuilder()
            
            // Send empty text with audio - the model will process the audio as the user's message
            agent.processInput(
                input = "",
                audioData = audioData
            ).collect { event ->
                when (event) {
                    is AgentEvent.Token -> {
                        responseBuilder.append(event.text)
                        _responseText.value = responseBuilder.toString()
                        _uiState.value = UIState.SPEAKING
                    }
                    
                    is AgentEvent.ExecutingTool -> {
                        _uiState.value = UIState.EXECUTING_TOOL
                        _responseText.value = "🔧 Using ${event.toolName}..."
                    }
                    
                    is AgentEvent.ToolResult -> {
                        // Tool completed, will continue to next iteration
                    }
                    
                    is AgentEvent.Complete -> {
                        val aiMessage = ConversationMessage(
                            text = event.response,
                            isUser = false
                        )
                        _messages.value = _messages.value + aiMessage
                        saveState()
                        
                        // Speak response if TTS enabled (auto mode is on since first input was voice)
                        speakResponse(event.response)
                        _uiState.value = UIState.IDLE
                    }
                    
                    is AgentEvent.Error -> {
                        Log.e(TAG, "Agent error: ${event.message}")
                        _responseText.value = "Error: ${event.message}"
                        _uiState.value = UIState.ERROR
                    }
                    
                    else -> {} // Handle other events if needed
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio", e)
            _uiState.value = UIState.ERROR
            _responseText.value = "Error: ${e.message}"
        }
    }
    
    private suspend fun processAudioWithServer(audioData: ByteArray) {
        val userMessage = ConversationMessage(
            text = "🎤 [Voice message]",
            isUser = true
        )
        _messages.value = _messages.value + userMessage
        saveState()

        val aiMessage = ConversationMessage(
            text = "Server mode doesn't support native audio understanding yet. Please use local model for voice input.",
            isUser = false
        )
        _messages.value = _messages.value + aiMessage
        saveState()
        _uiState.value = UIState.IDLE
    }

    fun sendTextMessage(text: String) {
        viewModelScope.launch {
            if (text.isBlank()) return@launch

            Log.d(TAG, "sendTextMessage called with: ${text.take(50)}...")
            Log.d(TAG, "Model loaded: ${llmEngine.isLoaded()}")

            // Track that first input was text for TTS auto mode
            if (firstInputWasVoice == null) {
                firstInputWasVoice = false
                Log.d(TAG, "First input was text - TTS auto-disabled")
            }

            val useServer = userSettings.enableServerDelegation.first()
            Log.d(TAG, "useServer: $useServer")
            
            if (useServer) {
                processTextWithServer(text)
            } else {
                processTextWithLocalModel(text)
            }
        }
    }
    
    private suspend fun processTextWithLocalModel(text: String) {
        Log.d(TAG, "processTextWithLocalModel started")
        val userMessage = ConversationMessage(
            text = text,
            isUser = true
        )
        _messages.value = _messages.value + userMessage
        saveState()

        _uiState.value = UIState.PROCESSING

        try {
            if (!llmEngine.isLoaded()) {
                Log.d(TAG, "Model not loaded, attempting to load...")
                val modelPath = userSettings.modelPath.first()
                Log.d(TAG, "Model path from settings: $modelPath")
                if (modelPath.isNotEmpty()) {
                    _responseText.value = "Loading model..."
                    val result = llmEngine.loadModel(modelPath)
                    result.onFailure { e ->
                        Log.e(TAG, "Failed to load model", e)
                        _uiState.value = UIState.ERROR
                        _responseText.value = "Failed to load model: ${e.message}"
                        return@processTextWithLocalModel
                    }
                    Log.d(TAG, "Model loaded successfully")
                    // Pre-warm cache after successful load
                    agent.prewarmCache()
                } else {
                    Log.w(TAG, "No model path configured")
                    _uiState.value = UIState.ERROR
                    _responseText.value = "No model loaded. Please go to Settings and load a model."
                    return
                }
            }

            Log.d(TAG, "Starting agent.processInput...")
            val responseBuilder = StringBuilder()
            
            agent.processInput(input = text).collect { event ->
                Log.d(TAG, "Agent event: $event")
                when (event) {
                    is AgentEvent.Token -> {
                        responseBuilder.append(event.text)
                        _responseText.value = responseBuilder.toString()
                        _uiState.value = UIState.SPEAKING
                    }
                    
                    is AgentEvent.ExecutingTool -> {
                        _uiState.value = UIState.EXECUTING_TOOL
                        _responseText.value = "🔧 Using ${event.toolName}..."
                    }
                    
                    is AgentEvent.ToolResult -> {
                        // Tool completed, will continue to next iteration
                    }
                    
                    is AgentEvent.Complete -> {
                        val aiMessage = ConversationMessage(
                            text = event.response,
                            isUser = false
                        )
                        _messages.value = _messages.value + aiMessage
                        saveState()
                        
                        // Speak response if TTS conditions met
                        speakResponse(event.response)
                        _uiState.value = UIState.IDLE
                    }
                    
                    is AgentEvent.Error -> {
                        Log.e(TAG, "Agent error: ${event.message}")
                        _responseText.value = "Error: ${event.message}"
                        _uiState.value = UIState.ERROR
                    }
                    
                    else -> {}
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
            _uiState.value = UIState.ERROR
            _responseText.value = "Error: ${e.message}"
        }
    }
    
    private suspend fun speakResponse(response: String) {
        val ttsEnabled = userSettings.ttsEnabled.first()
        val ttsAutoMode = userSettings.ttsAutoMode.first()
        
        val shouldSpeak = when {
            // TTS disabled completely
            !ttsEnabled -> false
            // Auto mode: speak if first input was voice, don't speak if first input was text
            ttsAutoMode -> firstInputWasVoice == true
            // Manual mode: speak if enabled
            else -> true
        }
        
        if (shouldSpeak) {
            Log.d(TAG, "Speaking response (firstInputWasVoice=$firstInputWasVoice, autoMode=$ttsAutoMode)")
            ttsService.speak(response) {
                // Callback when done speaking
            }
        } else {
            Log.d(TAG, "Skipping TTS (firstInputWasVoice=$firstInputWasVoice, autoMode=$ttsAutoMode)")
        }
    }
    
    private suspend fun processTextWithServer(text: String) {
        val userMessage = ConversationMessage(
            text = text,
            isUser = true
        )
        _messages.value = _messages.value + userMessage
        saveState()

        _uiState.value = UIState.PROCESSING

        val aiMessage = ConversationMessage(
            text = "Server mode not yet implemented. Please use local model.",
            isUser = false
        )
        _messages.value = _messages.value + aiMessage
        saveState()
        _uiState.value = UIState.IDLE
    }

    fun setResponse(text: String) {
        _responseText.value = text
        _uiState.value = UIState.SPEAKING
    }

    fun clearResponse() {
        _responseText.value = ""
    }

    fun clearMessages() {
        _messages.value = emptyList()
        firstInputWasVoice = null // Reset TTS auto mode
        agent.reset()
        saveState()
    }

    fun setError(message: String) {
        _responseText.value = message
        _uiState.value = UIState.ERROR
    }

    fun resetToIdle() {
        _uiState.value = UIState.IDLE
    }
    
    fun onImageSelected(bitmap: android.graphics.Bitmap?, text: String = "") {
        if (bitmap == null) {
            setError("Failed to load image")
            return
        }
        
        // Validate bitmap
        if (bitmap.width == 0 || bitmap.height == 0) {
            setError("Invalid image dimensions")
            return
        }
        
        Log.d(TAG, "Image selected: ${bitmap.width}x${bitmap.height}, text: '$text'")
        
        viewModelScope.launch {
            // Add image message to chat (with text if provided)
            val displayText = if (text.isNotBlank()) "🖼️ $text" else "🖼️ [Image]"
            val userMessage = ConversationMessage(
                text = displayText,
                isUser = true
            )
            _messages.value = _messages.value + userMessage
            saveState()
            
            firstInputWasVoice = false // Image is not voice input
            _uiState.value = UIState.PROCESSING
            
            try {
                if (!llmEngine.isLoaded()) {
                    val modelPath = userSettings.modelPath.first()
                    if (modelPath.isNotEmpty()) {
                        _responseText.value = "Loading model..."
                        val result = llmEngine.loadModel(modelPath)
                        result.onFailure { e ->
                            _uiState.value = UIState.ERROR
                            _responseText.value = "Failed to load model: ${e.message}"
                            return@launch
                        }
                        agent.prewarmCache()
                    } else {
                        _uiState.value = UIState.ERROR
                        _responseText.value = "No model loaded. Please go to Settings and load a model."
                        return@launch
                    }
                }
                
                val responseBuilder = StringBuilder()
                
                Log.d(TAG, "Processing image with model...")
                
                // Send empty text with image - model will process image naturally
                agent.processInput(
                    input = text, // Use the text the user typed (may be empty)
                    images = listOf(bitmap)
                ).collect { event ->
                    when (event) {
                        is AgentEvent.Token -> {
                            responseBuilder.append(event.text)
                            _responseText.value = responseBuilder.toString()
                            _uiState.value = UIState.SPEAKING
                        }
                        
                        is AgentEvent.ExecutingTool -> {
                            _uiState.value = UIState.EXECUTING_TOOL
                            _responseText.value = "🔧 Using ${event.toolName}..."
                        }
                        
                        is AgentEvent.ToolResult -> {
                            // Tool completed
                        }
                        
                        is AgentEvent.Complete -> {
                            val aiMessage = ConversationMessage(
                                text = event.response,
                                isUser = false
                            )
                            _messages.value = _messages.value + aiMessage
                            saveState()
                            
                            speakResponse(event.response)
                            _uiState.value = UIState.IDLE
                        }
                        
                        is AgentEvent.Error -> {
                            Log.e(TAG, "Agent error: ${event.message}")
                            _responseText.value = "Error: ${event.message}"
                            _uiState.value = UIState.ERROR
                        }
                        
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                _uiState.value = UIState.ERROR
                _responseText.value = "Error processing image: ${e.message}"
                
                // Add error message to chat
                val errorMessage = ConversationMessage(
                    text = "❌ Failed to process image: ${e.message}",
                    isUser = false
                )
                _messages.value = _messages.value + errorMessage
                saveState()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (audioRecorder.isRecording()) {
            // Fire and forget
        }
        ttsService.shutdown()
        llmEngine.unload()
    }
}

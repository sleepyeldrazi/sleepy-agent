# Sleepy Agent - Implementation Plan

## Overview

Privacy-first AI phone companion using Gemma 4 E2B/E4B with optional home server delegation. MVP focuses on audio pipeline and local inference.

---

## Phase 1: Project Setup

### 1.1 Project Structure
```
app/
├── src/main/
│   ├── java/com/sleepy/agent/
│   │   ├── MainActivity.kt
│   │   ├── ui/
│   │   │   ├── theme/
│   │   │   ├── components/
│   │   │   └── screens/
│   │   ├── inference/
│   │   │   ├── LlmEngine.kt
│   │   │   ├── ConversationContext.kt
│   │   │   ├── Agent.kt
│   │   │   └── ModelDownloader.kt
│   │   ├── audio/
│   │   │   ├── AudioRecorder.kt
│   │   │   ├── VoiceActivityDetector.kt
│   │   │   └── TtsService.kt
│   │   ├── camera/
│   │   │   └── CameraCapture.kt
│   │   ├── tools/
│   │   │   ├── Tool.kt
│   │   │   ├── WebSearchTool.kt
│   │   │   └── ServerTool.kt
│   │   ├── storage/
│   │   │   ├── AppDatabase.kt
│   │   │   └── SessionDao.kt
│   │   ├── di/                   # Hilt modules
│   │   │   └── AppModule.kt
│   │   └── settings/
│   │       ├── Preferences.kt      # DataStore-based settings
│   │       └── UserSettings.kt     # Typed preferences wrapper
│   └── assets/
│       └── models/          # Bundled .task files (future)
└── build.gradle.kts
```

### 1.2 Hilt Setup

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    @Provides
    @Singleton
    @Named("searxng")
    fun provideSearxngUrl(settings: UserSettings): String {
        return "http://sleepy-think:7777"
    }

    @Provides
    @Singleton
    fun provideKtorClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
            }
        }
    }
}
```

### 1.3 Dependencies
```kotlin
// MediaPipe LLM Inference for Gemma 4 E2B/E4B
// Models available in .task format on HuggingFace/Google AI Edge

// CameraX
implementation("androidx.camera:camera-core:latest")
implementation("androidx.camera:camera-camera2:latest")
implementation("androidx.camera:camera-lifecycle:latest")

// Room (for session history)
implementation("androidx.room:room-runtime:latest")
implementation("androidx.room:room-ktx:latest")

// Ktor Client (server API)
implementation("io.ktor:ktor-client-core:latest")
implementation("io.ktor:ktor-client-okhttp:latest")
implementation("io.ktor:ktor-client-content-negotiation:latest")
implementation("io.ktor:ktor-serialization-kotlinx-json:latest")

// Hilt (DI)
implementation("com.google.dagger:hilt-android:latest")
kapt("com.google.dagger:hilt-android-compiler:latest")

// DataStore (preferences)
implementation("androidx.datastore:datastore-preferences:latest")

// Kotlinx Serialization
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:latest")

// Compose
implementation(platform("androidx.compose:compose-bom:latest"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")

// Silero VAD (via TensorFlow Lite runtime)
implementation("org.tensorflow:tensorflow-lite-runtime:latest")
// Silero .tflite model file included in assets
```

> **Model Format**: Gemma 4 E2B/E4B models in `.task` format (MediaPipe bundle) from HuggingFace or Google AI Edge.

---

## Phase 2: Audio Pipeline

### 2.1 Recording
- `AudioRecorder.kt`: Record from mic using `AudioRecord`
- Chunked recording (e.g., 1-second buffers)
- Stream to VAD for activity detection

### 2.2 Voice Activity Detection
- **Silero VAD** (chosen over MediaPipe Speech for better accuracy)
- Detect speech vs silence
- Flow: **silence → speech detected → start capture → 2+ sec silence → stop & process**
- Silero model: `.tflite` file in assets, loaded via TensorFlow Lite runtime

### 2.3 TTS
- Android native `TextToSpeech`
- Fallback: MediaPipe TTS if needed

### 2.4 Audio → Model Input (Batch)
- **Batch audio only** — MediaPipe LLM Inference supports audio clips up to 30 seconds
- Streaming audio is on the roadmap, not available yet
- For PTT: silence detected → clip ends → send batch to model. This fits perfectly.
- Pass audio bytes with a simple transcription prompt to verify the pipeline

### 2.5 MVP Audio Test
Record 3-5 seconds of speech via `AudioRecord`, pass as batch to model with "transcribe this" prompt. Confirm text comes back. This single test validates the entire audio pipeline before building VAD, TTS, or tooling.

---

## Phase 3: Inference Engine (MediaPipe LLM Inference)

> **Runtime**: MediaPipe LLM Inference
> **Model format**: `.task` bundle (Gemma 4 E2B/E4B on HuggingFace/Google AI Edge)
> **Function calling**: Built-in support for agentic tool workflows

### 3.1 LlmEngine Integration (MediaPipe)
```kotlin
@Singleton
class LlmEngine @Inject constructor(
    private val context: Context
) {
    private var model: LlmInference? = null

    suspend fun loadModel(modelPath: String) {
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(16384)
            // For vision: enableVisionModality(true)
            .build()
        model = LlmInference.createFromFile(context, options)
    }

    suspend fun generate(
        input: String,
        audioBuffer: ByteArray? = null,  // batch audio up to 30s
        images: List<Bitmap>? = null     // up to 10 per session
    ): String {
        // Convert inputs to MPImage if provided
        val mpImages = images?.map { BitmapToMPImage(it) }
        val result = model?.generate(input, audioBuffer, mpImages)
        return result ?: ""
    }

    suspend fun generateStream(
        input: String,
        audioBuffer: ByteArray? = null,
        onToken: (String) -> Unit
    ) {
        // Streaming response for progressive UI display
        model?.generateStreaming(input, audioBuffer) { token ->
            onToken(token)
        }
    }
}
```

### 3.2 Image Input
```kotlin
// Convert Bitmap to MPImage, set EnableVisionModality(true) in session options
fun bitmapToMPImage(bitmap: Bitmap): MPImage {
    return MPImage.createFrom(bitmap)
}

// Usage: up to 10 images per session
val images = listOf(capturedPhoto)
llm.generate("What do you see?", images = images)
```

### 3.3 Tool Calling Harness
```kotlin
data class Tool(
    val name: String,
    val description: String,
    val parameters: Map<String, String>
)

// Parse model output for tool calls
fun parseToolCalls(response: String): List<ToolCall>? {
    // E2B/E4B use standard JSON tool calls
    // Parse <tool>...</tool> or function calls
}

// Execute tool and inject result
suspend fun executeToolCall(toolCall: ToolCall): String {
    return when (toolCall.name) {
        "web_search" -> webSearchTool.execute(toolCall.params)
        "delegate_to_server" -> serverTool.execute(toolCall.params)
        else -> "Unknown tool"
    }
}
```

### 3.4 System Prompt
```
You are a fast, concise AI assistant on a phone.
Keep responses short (1-3 sentences max).
You have these tools:
- web_search(query, maxResults): Search the web via SearXNG
- web_fetch(url, maxLength): Get content from a URL
- delegate_to_server(prompt): Ask the desktop AI for complex tasks

Use tools only when needed. For quick questions, answer directly.
When delegating, provide clear context for the desktop AI.
```

---

## Phase 3.5: ConversationContext Manager

The `ConversationContext` is the ephemeral state manager for the agentic loop. It lives in-memory per session and handles the orchestration between LLM, tools, and context window.

### 3.5.1 Core Data Structures (Hilt-injected)

```kotlin
@Serializable
sealed class Message {
    @Serializable
    data class User(val content: String) : Message()
    @Serializable
    data class Assistant(val content: String, val toolCalls: List<ToolCall>? = null) : Message()
    @Serializable
    data class ToolResult(val toolCallId: String, val toolName: String, val result: String) : Message()
    @Serializable
    data class System(val content: String) : Message()  // injected at start
}

@Serializable
data class ConversationContext(
    val sessionId: String,
    val systemPrompt: String,
    val messages: List<Message> = emptyList(),
    val maxTokens: Int = 16384,      // model context limit
    val reservedForResponse: Int = 2048  // keep room for model output
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String>
)
```

### 3.5.2 Token Budget Management

```kotlin
@Singleton
class ConversationContext @Inject constructor(
    private val maxTokens: Int = 16384
) {
    private val messages = mutableListOf<Message>()
    private val systemPrompt = """..."""

    // Rough token estimation: ~4 chars per token for English
    private fun estimateTokens(text: String): Int = text.length / 4

    // Check if adding new content would overflow context
    private fun canFit(content: String): Boolean {
        val used = messages.sumOf { estimateTokens(it.toText()) }
        val new = estimateTokens(content)
        return (used + new + reservedForResponse) <= maxTokens
    }

    // Trim oldest non-system messages until it fits
    fun addMessage(msg: Message): Boolean {
        messages.add(msg)
        return pruneIfNeeded()
    }

    private fun pruneIfNeeded(): Boolean {
        // Keep system prompt + last N messages until within budget
        while (!canFit("") && messages.size > 1) {
            // Remove oldest non-system message
            val idx = messages.indexOfFirst { it !is Message.System }
            if (idx >= 0) messages.removeAt(idx) else return false
        }
        return true
    }
}
```

### 3.5.3 Agentic Loop Orchestration (with Hilt DI)

```kotlin
@Singleton
class Agent @Inject constructor(
    private val llm: LlmEngine,
    private val tools: List<Tool>,
    private val context: ConversationContext
) {
    enum class State { IDLE, GENERATING, AWAITING_TOOL, STREAMING }

    // Main loop: runs until no more tool calls
    suspend fun run(userInput: String): Flow<String> = flow {
        context.addMessage(Message.User(userInput))

        var iterations = 0
        val maxIterations = 5  // prevent infinite loops

        while (iterations < maxIterations) {
            iterations++

            // Build prompt with all messages
            val prompt = buildPrompt(context.getMessages())

            // Stream response tokens
            val responseBuilder = StringBuilder()
            llm.generateStream(prompt) { token ->
                responseBuilder.append(token)
                emit(token)  // yield to UI
            }

            val response = responseBuilder.toString()
            context.addMessage(Message.Assistant(response, toolCalls))

            // Parse tool calls from response
            val toolCalls = parseToolCalls(response)
            if (toolCalls.isEmpty()) {
                emit(response)  // final response, we're done
                break
            }

            // Execute tools in parallel
            val results = toolCall.parMap { toolCall ->
                val result = executeTool(toolCall)
                Message.ToolResult(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    result = result
                )
            }

            // Inject results back into context
            results.forEach { context.addMessage(it) }
        }
    }

    // Build messages into a prompt format the model understands
    private fun buildPrompt(messages: List<Message>): String {
        return buildString {
            appendLine(systemPrompt)
            appendLine()
            messages.forEach { msg ->
                when (msg) {
                    is Message.System -> appendLine("<system>${msg.content}</system>")
                    is Message.User -> appendLine("<user>${msg.content}</user>")
                    is Message.Assistant -> appendLine("<assistant>${msg.content}</assistant>")
                    is Message.ToolResult -> appendLine("<tool_result id=\"${msg.toolCallId}\">${msg.result}</tool_result>")
                }
            }
            append("<assistant>")
        }
    }
}
```

### 3.5.4 Tool Parsing Strategy

E2B/E4B outputs tool calls in JSON within the response. Extract them:

```kotlin
fun parseToolCalls(response: String): List<ToolCall> {
    // Look for JSON array in response
    val jsonMatch = Regex("""\[.*?"name"\s*:\s*"(\w+)".*?\]""", RegexOption.DOT_MATCHES_ALL)
        .find(response, startIndex = response.indexOf("["))

    return jsonMatch?.let {
        try {
            val parser = Json { ignoreUnknownKeys = true }
            parser.decodeFromString<List<ToolCallDto>>(it.value)
                .map { dto -> ToolCall(uuid(), dto.name, dto.arguments ?: emptyMap()) }
        } catch (e: Exception) {
            emptyList()
        }
    } ?: emptyList()
}

@Serializable
data class ToolCallDto(
    val name: String,
    val arguments: Map<String, JsonElement>? = null
)
```

### 3.5.5 Session Lifecycle

```
┌─────────────────────────────────────────────────────────┐
│  App Launch                                              │
│  └─> Create ConversationContext(sessionId, systemPrompt) │
│       └─> Context is EMPTY, ready for first user input   │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│  User taps mic, speaks                                   │
│  └─> context.addMessage(User(audio_text))               │
│       └─> agent.run(userInput) — starts agentic loop     │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│  Loop: generate → parse tools → execute → inject result │
│  └─> context grows with each turn                        │
│  └─> prune() called automatically if near context limit │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│  User taps mic again (new turn)                          │
│  └─> context.messages is PERSISTED to Room (optional)   │
│  └─> NEW ConversationContext(sessionId) created          │
│       └─> Previous messages stored, fresh context built  │
└─────────────────────────────────────────────────────────┘
```

### 3.5.6 Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Tool execution | Sequential by default | Simplifies debugging; parallel optional for speed |
| Context pruning | Remove oldest non-system | Keeps recent context most relevant |
| Tool result injection | Prepend as `<tool_result>` | Model expects structured context |
| Max iterations | 5 | Prevents runaway loops; user can re-query |
| Streaming | Token-by-token Flow | UI can display progressively |

---

## Phase 4: Tools (Ktor Client)

### 4.1 Web Search (Ktor)
```kotlin
class WebSearchTool @Inject constructor(
    private val client: HttpClient,
    @Named("searxng") private val baseUrl: String
) {
    suspend fun execute(query: String, maxResults: Int = 8): String {
        return client.get("$baseUrl/search") {
            parameter("q", query)
            parameter("format", "json")
        }.bodyAsText()
    }
}
```

### 4.2 Web Fetch (Ktor)
```kotlin
class WebFetchTool @Inject constructor(
    private val client: HttpClient
) {
    suspend fun execute(url: String, maxLength: Int = 20000): String {
        return client.get(url).bodyAsText().take(maxLength)
    }
}
```

### 4.3 Server Delegation (Ktor)
```kotlin
class ServerTool @Inject constructor(
    private val client: HttpClient,
    private val settings: UserSettings  // DataStore
) {
    suspend fun execute(prompt: String): String {
        val serverUrl = settings.serverUrl.first()
        return client.post("$serverUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(buildChatRequest(prompt))
        }.body<ServerResponse>().choices.first().message.content
    }
}
```

### 4.4 Health Check (Ktor)
```kotlin
suspend fun checkServerHealth(url: String): Boolean {
    return try {
        val response = client.get("$url/v1/models")
        response.status.isSuccess()
    } catch (e: Exception) {
        false
    }
}
```

### 4.5 Model Discovery (Ktor)
```kotlin
@Serializable
data class ServerModel(val id: String)

suspend fun listServerModels(url: String): List<ServerModel> {
    return client.get("$url/v1/models")
        .body<ModelsResponse>()
        .data
}
```

---

## Phase 5: UI

### 5.1 Main Screen Layout
```
┌──────────────────────────────────────┐
│  [Settings ⚙️]            [Model 🤖]  │
├──────────────────────────────────────┤
│         ┌──────────────────┐         │
│         │                  │         │
│         │  Response here   │         │  <- 3-4 lines, small font
│         │                  │         │
│         └──────────────────┘         │
│                                      │
│         ┌──────────────────┐         │
│         │                  │         │
│         │   Camera Preview │         │  <- Tap to capture
│         │                  │         │
│         └──────────────────┘         │
│                                      │
│              [ 🎤 ]                  │  <- Large PTT button
│           "Tap to speak"             │
│                                      │
└──────────────────────────────────────┘
```

### 5.2 Settings Screen
- Model selector (E2B / E4B / Auto)
- Server URL input
- Server health indicator (green/red dot)
- Server model dropdown (populated from `/v1/models`)
- "Check Health" button
- Enable server delegation toggle (off by default)

### 5.3 States
- **Idle**: Waiting for input
- **Listening**: Recording audio, mic button pulses
- **Processing**: Model thinking, show loading
- **Speaking**: TTS playing response
- **Error**: "I'm unavailable right now" (hardcoded for offline homeserver/errors)

---

## Phase 6: Storage & RAG (Future)

### 6.1 Session Storage
```kotlin
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val messages: List<Message>  // JSON
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,  // "user" / "assistant"
    val content: String,
    val timestamp: Long,
    val toolsUsed: String?  // JSON array
)
```

### 6.2 RAG Pipeline (Future)
```
┌─────────────────────────────────────────────────────┐
│  Background Job (WorkManager)                         │
│                                                      │
│  Sessions → Embed (EmbeddingGemma) → Vector Store    │
│                                                      │
│  Query: embed(input) → similarity search → context   │
└─────────────────────────────────────────────────────┘
```

- EmbeddingGemma or on-device sentence transformer
- Vector store: SQLite with embeddings (or LanceDB if needed)
- Periodic: Daily digest of sessions older than 7 days
- Query: Include relevant past context in system prompt

---

## Phase 7: Model Onboarding

### Option A: Download from HuggingFace
- App shows "Download model" screen on first launch
- Fetch `.task` file from HuggingFace (or Google AI Edge)
- Show progress bar
- ~1-2GB depending on model (E2B vs E4B)

### Option B: User provides model file
- "Select model file" button in onboarding
- File picker for `.task` files
- User downloads manually from HuggingFace

### Option C: Point to file path
- "Enter model path" in settings
- Useful for users who already have the model downloaded

### MVP: Both download and file path options available
- Onboarding screen offers both choices
- Settings allows changing model location later

---

## Implementation Order

### Phase 1: Core Shell
1. Compose project setup
2. Main screen UI (static)
3. Settings screen
4. Model onboarding (download OR file picker)

### Phase 2: Audio Pipeline + Inference + MVP Test
1. **MVP Test First**: Record 3-5s audio, pass to LiteRT-LM, verify text back — confirms entire pipeline
2. Mic recording
3. Voice activity detection
4. Audio batch to model (up to 30s)
5. TTS output
6. **ConversationContext & Agent (agentic loop)**

### Phase 3: Tools & Server
1. Web search tool
2. Server delegation (disabled by default)
3. Settings: server URL, health check, model list
4. Tool parsing & execution harness

### Phase 4: Polish
1. State management
2. Error handling
3. Conversation history (basic)
4. Camera integration

### Future: RAG

---

## Key Decisions (Confirmed)

- [x] **Runtime**: MediaPipe LLM Inference (Gemma 4 E2B/E4B)
- [x] **Model format**: `.task` bundle from HuggingFace/Google AI Edge
- [x] **Audio input**: Batch only, up to 30 seconds per clip (streaming on roadmap)
- [x] **Image input**: Up to 10 per session via MPImage + EnableVisionModality
- [x] **ASR approach**: Direct audio to model (batch) — no separate transcription step
- [x] **VAD library**: **Silero VAD** (`.tflite` model via TensorFlow Lite runtime)
- [x] **TTS**: Native Android `TextToSpeech`
- [x] **Model delivery**: Option to download from HuggingFace OR point to local file
- [x] **Context management**: `ConversationContext` with token budgeting and auto-pruning
- [x] **Tool format**: `<tool_result>` XML-style tags for injecting results
- [x] **Max iterations**: 5 tool calls per user turn to prevent runaway loops
- [x] **Networking**: Ktor Client (coroutines-native, replaces Retrofit)
- [x] **DI**: Hilt (replaces manual dependency management)
- [x] **Preferences**: DataStore (replaces SharedPreferences)
- [x] **Serialization**: Kotlinx Serialization (for Room, Ktor, DataStore)

---

## Resources

- [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/generative-ai/llm_inference) — On-device Gemma 4 inference
- [Gemma 4 E2B/E4B on HuggingFace](https://huggingface.co/models?search=gemma-4-e2b) — Models in `.task` format
- [Silero VAD](https://github.com/silero-vad/silero-vad) — Voice activity detection (.tflite)
- [Jetpack Compose](https://developer.android.com/compose)
- [Hilt](https://developer.android.com/training/dependency-injection/hilt-android)
- [Ktor Client](https://ktor.io/docs/client.html)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [CameraX](https://developer.android.com/camera)

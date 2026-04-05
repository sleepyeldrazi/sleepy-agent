# Sleepy Agent - Implementation Plan

## Overview

Privacy-first AI phone companion using E2B/E4B with optional home server delegation. MVP focuses on audio pipeline and local inference.

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
│   │   └── settings/
│   │       └── Preferences.kt
│   └── assets/
│       └── models/          # Bundled .task files (future)
└── build.gradle.kts
```

### 1.2 Dependencies
```kotlin
// LiteRT-LM (replaces deprecated MediaPipe tasks-genai)
implementation("com.google.mediapipe:tasks-text:latest.version")
// LiteRT-LM Android artifact — see https://ai.google.dev/edge/litert_lm
// Available on MavenCentral as litert-litertl or bundled with tasks-genai
// Gemma 3n E2B/E4B models available in .litertlm format on HuggingFace

// CameraX
implementation("androidx.camera:camera-core:latest")
implementation("androidx.camera:camera-camera2:latest")
implementation("androidx.camera:camera-lifecycle:latest")

// Room
implementation("androidx.room:room-runtime:latest")
implementation("androidx.room:room-ktx:latest")

// Networking
implementation("com.squareup.okhttp3:okhttp:latest")

// Compose
implementation(platform("androidx.compose:compose-bom:latest"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
```

> **Note**: MediaPipe LLM Inference API is deprecated. Use **LiteRT-LM** as the production runtime. Gemma 3n E2B/E4B models in `.litertlm` format are available on HuggingFace.

---

## Phase 2: Audio Pipeline

### 2.1 Recording
- `AudioRecorder.kt`: Record from mic using `AudioRecord`
- Chunked recording (e.g., 1-second buffers)
- Stream to VAD for activity detection

### 2.2 Voice Activity Detection
- Detect speech vs silence
- Flow: **silence → speech detected → start capture → 2+ sec silence → stop & process**
- Option: MediaPipe speech recognition or simple amplitude thresholding

### 2.3 TTS
- Android native `TextToSpeech`
- Fallback: MediaPipe TTS if needed

### 2.4 Audio → Model Input (Batch)
- **Batch audio only** — LiteRT-LM supports audio clips up to 30 seconds
- Streaming audio is on the roadmap, not available yet
- For PTT: silence detected → clip ends → send batch to model. This fits perfectly.
- Pass audio bytes with a simple transcription prompt to verify the pipeline

### 2.5 MVP Audio Test
Record 3-5 seconds of speech via `AudioRecord`, pass as batch to model with "transcribe this" prompt. Confirm text comes back. This single test validates the entire audio pipeline before building VAD, TTS, or tooling.

---

## Phase 3: Inference Engine (LiteRT-LM)

> **Runtime**: LiteRT-LM (successor to deprecated MediaPipe LLM Inference API)
> **Model format**: `.litertlm` (Gemma 3n E2B/E4B on HuggingFace)
> **Function calling**: Built-in support for agentic tool workflows

### 3.1 LiteRT-LM Integration
```kotlin
class LlmEngine(private val context: Context) {
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

### 3.5.1 Core Data Structures

```kotlin
sealed class Message {
    data class User(val content: String) : Message()
    data class Assistant(val content: String, val toolCalls: List<ToolCall>? = null) : Message()
    data class ToolResult(val toolCallId: String, val toolName: String, val result: String) : Message()
    data class System(val content: String) : Message()  // injected at start
}

data class ConversationContext(
    val sessionId: String,
    val systemPrompt: String,
    val messages: List<Message> = emptyList(),
    val maxTokens: Int = 16384,      // model context limit
    val reservedForResponse: Int = 2048  // keep room for model output
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String>
)
```

### 3.5.2 Token Budget Management

```kotlin
class ConversationContext(private val maxTokens: Int) {
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

### 3.5.3 Agentic Loop Orchestration

```kotlin
class Agent(
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
            val results = toolCalls.map { toolCall ->
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

## Phase 4: Tools

### 4.1 Web Search
- Endpoint: `http://sleepy-think:7777/search?q={query}&format=json`
- Parameters: `query`, `maxResults` (default 8)
- Response: JSON with `results[]` containing `title`, `url`, `content`

### 4.2 Web Fetch
- Endpoint: `http://sleepy-think:7777/search?q={url}` (or direct fetch)
- Strip HTML, return plain text
- Truncate at `maxLength` (default 20k)

### 4.3 Server Delegation
- Endpoint: Configured in settings (e.g., `http://home-server:1234`)
- POST `/v1/completions` or `/v1/chat/completions`
- System override: "You are helping via phone. Keep responses concise."
- Timeout: 60s, show "thinking..." state

### 4.4 Health Check
```kotlin
suspend fun checkServerHealth(url: String): Boolean {
    return try {
        val response = client.get("$url/v1/models")
        response.isSuccessful
    } catch (e: Exception) {
        false
    }
}
```

### 4.5 Model Discovery
```kotlin
data class ServerModel(val id: String, val object: String = "model")

suspend fun listServerModels(url: String): List<ServerModel> {
    val response = client.get("$url/v1/models")
    val body = response.body?.string() ?: return emptyList()
    return parseJson(body).get("data").map { ... }
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

- [x] **Runtime**: LiteRT-LM (successor to deprecated MediaPipe LLM Inference API)
- [x] **Model format**: `.litertlm` from HuggingFace (Gemma 3n E2B/E4B)
- [x] **Audio input**: Batch only, up to 30 seconds per clip (streaming on roadmap)
- [x] **Image input**: Up to 10 per session via MPImage + EnableVisionModality
- [x] **ASR approach**: Direct audio to model (batch) — no separate transcription step
- [x] **VAD library**: MediaPipe Speech or Silero VAD
- [x] **TTS**: Native Android `TextToSpeech`
- [x] **Model delivery**: Option to download from HuggingFace OR point to local file
- [x] **Context management**: `ConversationContext` with token budgeting and auto-pruning
- [x] **Tool format**: `<tool_result>` XML-style tags for injecting results
- [x] **Max iterations**: 5 tool calls per user turn to prevent runaway loops

---

## Resources

- [LiteRT-LM](https://ai.google.dev/edge/litert_lm) — Production runtime (replaces deprecated MediaPipe LLM Inference)
- [Gemma 3n E2B/E4B on HuggingFace](https://huggingface.co/models?search=gemma-3n-e2b) — Models in `.litertlm` format
- [Jetpack Compose](https://developer.android.com/compose)
- [CameraX](https://developer.android.com/camera)
- [flutter_gemma](https://pub.dev/packages/flutter_gemma) — Reference implementation for Android audio pipeline with Gemma 4 E2B/E4B (source on GitHub)

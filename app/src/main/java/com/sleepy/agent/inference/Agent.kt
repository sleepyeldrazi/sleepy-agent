package com.sleepy.agent.inference

import android.util.Log
import com.sleepy.agent.tools.Tool
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Agent that manages conversation with the LLM, including tool calling.
 * 
 * Supports multiple Gemma 4 tool call formats:
 * 1. JSON format: <|tool_call>call:tool_name{"name": "tool_name", "arguments": {...}}<tool_call|>
 * 2. Direct args: <|tool_call>call:tool_name{query: "value"}<tool_call|>
 * 3. Special tokens: <|tool_call>call:tool_name{query:<|"|>value<|"|>}<tool_call|>
 * 4. Old format: <tool_call>{"name": "tool_name", ...}</tool_call>
 */
class Agent(
    private val llmEngine: LlmEngine,
    private val context: ConversationContext,
    private val tools: Map<String, Tool>
) {
    enum class State { IDLE, GENERATING, EXECUTING_TOOL, STREAMING, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    companion object {
        private const val TAG = "Agent"
        private const val MAX_ITERATIONS = 5
        private val jsonParser = Json { ignoreUnknownKeys = true }
        
        // Supported tool call patterns
        private val TOOL_PATTERNS = listOf(
            Pair("<|tool_call>call:", "<tool_call|>"),
            Pair("<|tool_call>", "<tool_call|>"),
            Pair("<tool_call>", "</tool_call>")
        )
    }

    private var conversation: Conversation? = null
    
    private val systemPrompt = buildString {
        appendLine("You are a helpful AI assistant with access to tools.")
        appendLine()
        appendLine("Available tools:")
        appendLine("- web_search: Search the web for information. Parameters: query (string, required)")
        appendLine("- home_server: Execute commands on the home server. Parameters: command (string, required), args (string, optional)")
        appendLine()
        appendLine("When you need to use a tool, you MUST output EXACTLY in this format:")
        appendLine("<|tool_call>call:web_search{\"name\": \"web_search\", \"arguments\": {\"query\": \"your search query here\"}}<tool_call|>")
        appendLine()
        appendLine("IMPORTANT:")
        appendLine("- Replace 'web_search' with the actual tool name you want to use")
        appendLine("- Do NOT use 'tool_name' as a placeholder - use the real tool name")
        appendLine("- For web_search, use: {\"name\": \"web_search\", \"arguments\": {\"query\": \"...\"}}")
        appendLine("- For home_server, use: {\"name\": \"home_server\", \"arguments\": {\"command\": \"...\"}}")
        appendLine()
        appendLine("After receiving tool results, provide a helpful response to the user.")
    }

    private fun ensureConversation(): Conversation {
        if (conversation?.isAlive != true) {
            Log.d(TAG, "Creating new conversation")
            conversation = llmEngine.createConversation(systemPrompt)
        }
        return conversation!!
    }

    suspend fun prewarmCache() {
        try {
            Log.d(TAG, "Pre-warming KV cache with system prompt...")
            ensureConversation()
            Log.d(TAG, "KV cache pre-warmed and ready")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pre-warm cache", e)
        }
    }

    suspend fun processInput(
        input: String,
        audioData: ByteArray? = null,
        images: List<android.graphics.Bitmap>? = null,
        onToken: ((String) -> Unit)? = null
    ): Flow<AgentEvent> = channelFlow {
        Log.d(TAG, "processInput called with input: ${input.take(50)}...")
        _state.value = State.GENERATING

        context.addMessage(Message.User(input))
        val conv = ensureConversation()
        Log.d(TAG, "Conversation ensured, alive: ${conv.isAlive}")

        var iteration = 0
        var audioDataForIteration = audioData
        var imagesForIteration = images

        try {
            while (iteration < MAX_ITERATIONS) {
                iteration++
                Log.d(TAG, "Iteration $iteration")

                val prompt = if (iteration == 1) input else context.buildPrompt()

                _state.value = State.GENERATING
                val responseBuilder = StringBuilder()
                
                try {
                    Log.d(TAG, "Calling llmEngine.generateStream...")
                    llmEngine.generateStream(
                        conversation = conv,
                        prompt = prompt,
                        audioData = audioDataForIteration,
                        images = imagesForIteration
                    ) { token ->
                        responseBuilder.append(token)
                    }
                    Log.d(TAG, "generateStream completed, response length: ${responseBuilder.length}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during generation", e)
                    send(AgentEvent.Error("Generation failed: ${e.message}"))
                    _state.value = State.ERROR
                    return@channelFlow
                }

                audioDataForIteration = null
                imagesForIteration = null

                val fullResponse = responseBuilder.toString()
                Log.d(TAG, "Raw response length: ${fullResponse.length}, content: ${fullResponse.take(200)}...")

                val toolCalls = parseToolCalls(fullResponse)

                if (toolCalls.isEmpty()) {
                    _state.value = State.STREAMING
                    fullResponse.chunked(1).forEach { chunk ->
                        send(AgentEvent.Token(chunk))
                        onToken?.invoke(chunk)
                        kotlinx.coroutines.delay(5)
                    }
                    
                    context.addMessage(Message.Assistant(content = fullResponse, toolCalls = null))
                    send(AgentEvent.Complete(fullResponse))
                    _state.value = State.IDLE
                    return@channelFlow
                }

                Log.d(TAG, "Found ${toolCalls.size} tool call(s): ${toolCalls.map { it.name }}")
                _state.value = State.EXECUTING_TOOL
                
                val contentBeforeTools = extractContentBeforeTools(fullResponse)
                
                toolCalls.forEach { toolCall ->
                    val toolDisplayName = tools[toolCall.name]?.displayName ?: toolCall.name
                    send(AgentEvent.ExecutingTool(toolDisplayName, toolCall.arguments))
                }

                val toolResults = executeToolCalls(toolCalls)
                
                context.addMessage(Message.Assistant(content = contentBeforeTools, toolCalls = toolCalls))
                toolResults.forEach { result ->
                    context.addMessage(Message.ToolResult(toolCallId = result.id, toolName = result.name, result = result.result))
                    send(AgentEvent.ToolResult(result.name, result.result))
                }

                Log.d(TAG, "Tool results added, getting final response")
            }

            _state.value = State.GENERATING
            val finalResponse = llmEngine.generate(
                conversation = conv,
                prompt = context.buildPrompt(),
                audioData = null,
                images = null
            )
            
            _state.value = State.STREAMING
            finalResponse.chunked(1).forEach { chunk ->
                send(AgentEvent.Token(chunk))
                onToken?.invoke(chunk)
                kotlinx.coroutines.delay(5)
            }
            
            context.addMessage(Message.Assistant(content = finalResponse, toolCalls = null))
            send(AgentEvent.Complete(finalResponse))
            _state.value = State.IDLE

        } catch (e: Exception) {
            Log.e(TAG, "Error processing input", e)
            _state.value = State.ERROR
            send(AgentEvent.Error(e.message ?: "Unknown error"))
            throw e
        }
    }

    fun reset() {
        Log.d(TAG, "Resetting conversation")
        conversation?.close()
        conversation = null
        context.clear()
        _state.value = State.IDLE
    }

    /**
     * Parse tool calls from model response.
     * Handles multiple Gemma 4 formats.
     */
    private fun parseToolCalls(response: String): List<ToolCall> {
        val toolCalls = mutableListOf<ToolCall>()
        
        for ((startTag, endTag) in TOOL_PATTERNS) {
            var currentIndex = 0
            
            while (true) {
                val startIdx = response.indexOf(startTag, currentIndex)
                if (startIdx == -1) break
                
                val endIdx = response.indexOf(endTag, startIdx + startTag.length)
                if (endIdx == -1) break
                
                val content = response.substring(startIdx + startTag.length, endIdx).trim()
                
                parseToolCallContent(content)?.let { toolCalls.add(it) }
                
                currentIndex = endIdx + endTag.length
            }
        }

        return toolCalls
    }

    /**
     * Parse individual tool call content.
     * Handles:
     * - tool_name{"name": "...", "arguments": {...}}
     * - tool_name{query: "value"}
     * - tool_name{query:<|"|>value<|"|>}
     * - {"name": "...", ...} (legacy)
     */
    private fun parseToolCallContent(content: String): ToolCall? {
        Log.d(TAG, "Parsing tool call content: $content")
        
        // Clean up special quote tokens first
        val cleaned = content
            .replace("<|\">", "\"")
            .replace("<|\"|>", "\"")
            .replace("\">|>", "\"")
        
        return when {
            // Has braces - parse as {args} or tool_name{args}
            cleaned.contains("{") -> {
                val braceIdx = cleaned.indexOf("{")
                val toolNamePart = cleaned.substring(0, braceIdx).trim()
                val inner = cleaned.substring(braceIdx)
                
                // Try JSON first, then direct args
                parseAsJson(toolNamePart, inner) 
                    ?: parseAsDirectArgs(toolNamePart, inner)
            }
            // Try as pure JSON
            cleaned.trim().startsWith("{") -> {
                parseAsJson("", cleaned)
            }
            else -> {
                Log.w(TAG, "Unrecognized tool call format: $content")
                null
            }
        }
    }

    private fun parseAsJson(toolNamePrefix: String, jsonStr: String): ToolCall? {
        return try {
            val obj = jsonParser.parseToJsonElement(jsonStr).jsonObject
            
            // Get tool name from "name" field or prefix
            val toolName = obj["name"]?.jsonPrimitive?.content 
                ?: toolNamePrefix.takeIf { it.isNotEmpty() }
                ?: return null
            
            // Get arguments from "arguments" field or top-level
            val args = obj["arguments"]?.jsonObject?.let { argsObj ->
                argsObj.entries.associate { (k, v) ->
                    k to (v.jsonPrimitive.content ?: v.toString())
                }
            } ?: obj.entries
                .filter { it.key != "name" }
                .associate { (k, v) ->
                    k to (v.jsonPrimitive.content ?: v.toString())
                }
            
            ToolCall(id = generateToolCallId(), name = toolName, arguments = args)
        } catch (e: Exception) {
            Log.d(TAG, "JSON parse failed: ${e.message}")
            null
        }
    }

    private fun parseAsDirectArgs(toolName: String, argsStr: String): ToolCall? {
        val args = mutableMapOf<String, String>()
        
        // Extract content between outer braces
        val inner = argsStr.trim().trim('{', '}')
        
        // Split by comma, but be careful with nested structures
        var depth = 0
        var current = StringBuilder()
        val parts = mutableListOf<String>()
        
        for (char in inner) {
            when (char) {
                '{', '[' -> {
                    depth++
                    current.append(char)
                }
                '}', ']' -> {
                    depth--
                    current.append(char)
                }
                ',' -> {
                    if (depth == 0) {
                        parts.add(current.toString().trim())
                        current = StringBuilder()
                    } else {
                        current.append(char)
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) {
            parts.add(current.toString().trim())
        }
        
        // Parse each key:value pair
        for (part in parts) {
            val colonIdx = part.indexOf(':')
            if (colonIdx != -1) {
                val key = part.substring(0, colonIdx).trim().trim('"', '\'')
                var value = part.substring(colonIdx + 1).trim()
                
                // Clean up value
                value = value.trim('"', '\'', '{', '}')
                
                if (key.isNotEmpty()) {
                    args[key] = value
                }
            }
        }
        
        return if (args.isNotEmpty() && toolName.isNotEmpty()) {
            ToolCall(id = generateToolCallId(), name = toolName, arguments = args)
        } else null
    }

    private fun extractContentBeforeTools(response: String): String {
        var firstIdx = -1
        for ((startTag, _) in TOOL_PATTERNS) {
            val idx = response.indexOf(startTag)
            if (idx != -1 && (firstIdx == -1 || idx < firstIdx)) {
                firstIdx = idx
            }
        }
        
        return if (firstIdx != -1) {
            response.substring(0, firstIdx).trim()
        } else {
            response.trim()
        }
    }

    private suspend fun executeToolCalls(toolCalls: List<ToolCall>): List<ToolCallResult> = coroutineScope {
        toolCalls.map { toolCall ->
            val tool = tools[toolCall.name]
            val result = if (tool != null) {
                try {
                    Log.d(TAG, "Executing tool: ${toolCall.name}")
                    tool.execute(toolCall.arguments)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing tool '${toolCall.name}'", e)
                    "Error: ${e.message}"
                }
            } else {
                "Tool '${toolCall.name}' not found"
            }
            ToolCallResult(toolCall.id, toolCall.name, result)
        }
    }

    private fun generateToolCallId(): String = "call_${UUID.randomUUID().toString().take(8)}"

    private data class ToolCallResult(val id: String, val name: String, val result: String)
}

sealed class AgentEvent {
    data class Token(val text: String) : AgentEvent()
    data class ExecutingTool(val toolName: String, val arguments: Map<String, String>) : AgentEvent()
    data class ToolResult(val toolName: String, val result: String) : AgentEvent()
    data class Complete(val response: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

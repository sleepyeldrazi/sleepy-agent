package com.sleepy.agent.inference

import kotlinx.serialization.Serializable

@Serializable
sealed class Message {
    @Serializable
    data class User(val content: String) : Message()

    @Serializable
    data class Assistant(val content: String, val toolCalls: List<ToolCall>? = null) : Message()

    @Serializable
    data class ToolResult(val toolCallId: String, val toolName: String, val result: String) : Message()

    @Serializable
    data class System(val content: String) : Message()
}

@Serializable
data class ToolCall(val id: String, val name: String, val arguments: Map<String, String>)

class ConversationContext(
    private val systemPrompt: String = "You are a helpful AI assistant.",
    private val maxTokens: Int = 32768,  // Gemma 4 supports up to 32k context
    private val reservedForResponse: Int = 4096  // Reserve more for longer responses
) {
    private val messages = mutableListOf<Message>()
    private val effectiveBudget = maxTokens - reservedForResponse

    companion object {
        const val CHARS_PER_TOKEN = 4
    }

    /**
     * Adds a message to the conversation context.
     * Automatically prunes oldest non-system messages if token budget is exceeded.
     *
     * @param message The message to add
     * @return true if message was added successfully, false if it couldn't fit even after pruning
     */
    fun addMessage(message: Message): Boolean {
        val messageTokens = estimateTokens(message.toText())

        // If even with empty context this message exceeds budget, reject it
        if (messageTokens > effectiveBudget) {
            return false
        }

        messages.add(message)

        // Prune if necessary to stay within budget
        pruneIfNeeded()

        return true
    }

    /**
     * Returns all messages in the conversation, including system prompt as first message.
     */
    fun getMessages(): List<Message> {
        return listOf(Message.System(systemPrompt)) + messages
    }

    /**
     * Builds a formatted prompt string with XML-style tags for LLM consumption.
     */
    fun buildPrompt(): String {
        val sb = StringBuilder()

        // System message first
        sb.append("<system>").append(escapeXml(systemPrompt)).append("</system>\n")

        // User messages
        messages.forEach { message ->
            when (message) {
                is Message.User -> {
                    sb.append("<user>").append(escapeXml(message.content)).append("</user>\n")
                }
                is Message.Assistant -> {
                    sb.append("<assistant>")
                    sb.append(escapeXml(message.content))
                    message.toolCalls?.let { toolCalls ->
                        toolCalls.forEach { toolCall ->
                            sb.append("\n<tool_call id=\"").append(escapeXml(toolCall.id)).append("\"")
                            sb.append(" name=\"").append(escapeXml(toolCall.name)).append("\">")
                            val argsStr = toolCall.arguments.entries.joinToString(", ") { (k, v) ->
                                "\"${escapeXml(k)}\": \"${escapeXml(v)}\""
                            }
                            sb.append("{").append(argsStr).append("}")
                            sb.append("</tool_call>")
                        }
                    }
                    sb.append("</assistant>\n")
                }
                is Message.ToolResult -> {
                    sb.append("<tool_result")
                    sb.append(" id=\"").append(escapeXml(message.toolCallId)).append("\"")
                    sb.append(" tool=\"").append(escapeXml(message.toolName)).append("\">")
                    sb.append(escapeXml(message.result))
                    sb.append("</tool_result>\n")
                }
                is Message.System -> {
                    // System messages from the list shouldn't appear here
                    // (only the constructor systemPrompt is used)
                }
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * Estimates token count for given text.
     * Uses a simple heuristic: ~4 characters per token for English text.
     */
    fun estimateTokens(text: String): Int {
        return text.length / CHARS_PER_TOKEN
    }

    /**
     * Clears all conversation messages (except system prompt).
     */
    fun clear() {
        messages.clear()
    }

    /**
     * Returns the current total token count including system prompt.
     */
    fun getTokenCount(): Int {
        val systemTokens = estimateTokens(systemPrompt)
        val messagesTokens = messages.sumOf { estimateTokens(it.toText()) }
        return systemTokens + messagesTokens
    }

    /**
     * Returns the available token budget for new messages.
     */
    fun getAvailableTokens(): Int {
        return effectiveBudget - getTokenCount() + estimateTokens(systemPrompt)
    }

    /**
     * Returns all messages for serialization (for state restoration).
     */
    fun getSerializableMessages(): List<Message> = messages.toList()

    /**
     * Restores messages from serialization.
     */
    fun restoreMessages(msgs: List<Message>) {
        messages.clear()
        messages.addAll(msgs)
    }

    private fun pruneIfNeeded() {
        while (getTokenCount() > effectiveBudget && messages.isNotEmpty()) {
            // Find and remove the oldest non-system message
            // Keep removing from the beginning until we're under budget
            val indexToRemove = messages.indexOfFirst { it !is Message.System }
            if (indexToRemove == -1) {
                // No more non-system messages to remove
                break
            }
            messages.removeAt(indexToRemove)
        }
    }

    private fun Message.toText(): String {
        return when (this) {
            is Message.User -> content
            is Message.Assistant -> content + (toolCalls?.joinToString { "${it.name} ${it.arguments}" } ?: "")
            is Message.ToolResult -> "$toolName $result"
            is Message.System -> content
        }
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}

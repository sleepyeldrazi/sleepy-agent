package com.sleepy.agent.data

import android.content.Context
import android.util.Log
import com.sleepy.agent.ui.screens.ConversationMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Simple JSON-based conversation storage.
 * Stores chat history as files in app's private directory.
 */
class ConversationStorage(private val context: Context) {
    
    private val storageDir = File(context.filesDir, "conversations")
    private val json = Json { ignoreUnknownKeys = true }
    
    init {
        storageDir.mkdirs()
    }
    
    companion object {
        private const val TAG = "ConversationStorage"
        private const val MAX_CONVERSATIONS = 50
    }
    
    /**
     * Saves a conversation to storage.
     */
    fun saveConversation(id: String, messages: List<ConversationMessage>): Boolean {
        return try {
            val conversation = SavedConversation(
                id = id,
                title = generateTitle(messages),
                timestamp = System.currentTimeMillis(),
                messages = messages
            )
            
            val file = File(storageDir, "$id.json")
            file.writeText(json.encodeToString(conversation))
            
            // Clean up old conversations if needed
            cleanupOldConversations()
            
            Log.d(TAG, "Saved conversation $id with ${messages.size} messages")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save conversation", e)
            false
        }
    }
    
    /**
     * Loads a conversation from storage.
     */
    fun loadConversation(id: String): List<ConversationMessage>? {
        return try {
            val file = File(storageDir, "$id.json")
            if (!file.exists()) return null
            
            val conversation = json.decodeFromString<SavedConversation>(file.readText())
            Log.d(TAG, "Loaded conversation $id with ${conversation.messages.size} messages")
            conversation.messages
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversation", e)
            null
        }
    }
    
    /**
     * Deletes a conversation.
     */
    fun deleteConversation(id: String): Boolean {
        return try {
            val file = File(storageDir, "$id.json")
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete conversation", e)
            false
        }
    }
    
    /**
     * Gets all saved conversations sorted by most recent.
     */
    fun getAllConversations(): List<ConversationInfo> {
        return try {
            storageDir.listFiles { file -> file.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        val conversation = json.decodeFromString<SavedConversation>(file.readText())
                        ConversationInfo(
                            id = conversation.id,
                            title = conversation.title,
                            timestamp = conversation.timestamp,
                            messageCount = conversation.messages.size
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.sortedByDescending { it.timestamp }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list conversations", e)
            emptyList()
        }
    }
    
    /**
     * Creates a new conversation ID.
     */
    fun createNewConversationId(): String {
        return UUID.randomUUID().toString()
    }
    
    private fun generateTitle(messages: List<ConversationMessage>): String {
        // Use first user message as title, truncated
        val firstUserMessage = messages.firstOrNull { it.isUser }
        return firstUserMessage?.text?.take(50)?.let {
            if (firstUserMessage.text.length > 50) "$it..." else it
        } ?: "New Chat"
    }
    
    private fun cleanupOldConversations() {
        val conversations = getAllConversations()
        if (conversations.size > MAX_CONVERSATIONS) {
            conversations.drop(MAX_CONVERSATIONS).forEach {
                deleteConversation(it.id)
            }
        }
    }
}

@Serializable
data class SavedConversation(
    val id: String,
    val title: String,
    val timestamp: Long,
    val messages: List<ConversationMessage>
)

data class ConversationInfo(
    val id: String,
    val title: String,
    val timestamp: Long,
    val messageCount: Int
)

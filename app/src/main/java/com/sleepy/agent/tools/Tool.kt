package com.sleepy.agent.tools

/**
 * Interface for tools that can be called by the Agent.
 */
interface Tool {
    val name: String
    val displayName: String
    val description: String
    suspend fun execute(arguments: Map<String, String>): String
}

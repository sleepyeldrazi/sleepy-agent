package com.sleepy.agent.tools

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ServerTool(
    private val client: HttpClient,
    private val baseUrl: String = "http://sleepy-think:8000"
) : Tool {

    override val name: String = "home_server"
    override val displayName: String = "Home Server"
    override val description: String = "Execute commands on the home server. Parameters: command (string), args (optional string)"

    override suspend fun execute(arguments: Map<String, String>): String {
        val command = arguments["command"] ?: return "Error: 'command' parameter is required"
        val args = arguments["args"]?.split(",")?.map { it.trim() } ?: emptyList()
        return executeSync(command, args)
    }

    /**
     * Synchronous version for tool calling.
     */
    fun executeSync(command: String, args: List<String> = emptyList()): String {
        return try {
            kotlinx.coroutines.runBlocking {
                val response: JsonObject = client.post("$baseUrl/execute") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("command", command)
                        put("args", args.joinToString(","))
                    })
                }.body()

                response["result"]?.toString()
                    ?: response["output"]?.toString()
                    ?: "Command executed successfully"
            }
        } catch (e: Exception) {
            "Error executing server command: ${e.message}"
        }
    }
}

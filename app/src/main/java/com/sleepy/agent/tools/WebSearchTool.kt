package com.sleepy.agent.tools

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class WebSearchTool(
    private val client: HttpClient,
    private var baseUrl: String
) : Tool {

    override val name: String = "web_search"
    override val displayName: String = "Web Search"
    override val description: String = "Search the web for information. Parameters: query (string)"
    
    /**
     * Updates the base URL for the search endpoint.
     * Call this when the server URL setting changes.
     */
    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl.trim().trimEnd('/')
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: Map<String, String>): String {
        val query = arguments["query"] ?: return "Error: 'query' parameter is required"
        return executeSync(query)
    }

    /**
     * Synchronous version for tool calling.
     */
    fun executeSync(query: String): String {
        return try {
            kotlinx.coroutines.runBlocking {
                val response: SearxngResponse = client.get("$baseUrl/search") {
                    parameter("q", query)
                    parameter("format", "json")
                    parameter("safesearch", "0")
                }.body()

                if (response.results.isEmpty()) {
                    "No results found for '$query'"
                } else {
                    response.results.take(5).joinToString("\n\n") { result ->
                        buildString {
                            append("Title: ${result.title}\n")
                            append("URL: ${result.url}\n")
                            append("Content: ${result.content}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            "Error performing web search: ${e.message}"
        }
    }

    @Serializable
    data class SearxngResponse(
        val query: String = "",
        val results: List<SearchResult> = emptyList()
    )

    @Serializable
    data class SearchResult(
        val title: String = "",
        val url: String = "",
        val content: String = "",
        val engine: String = ""
    )
}

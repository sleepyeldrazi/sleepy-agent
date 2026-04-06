package com.sleepy.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Standalone test harness for debugging tool call parsing.
 * Run with: kotlinc -script ToolCallParserTest.kt
 * Or compile and run as regular Kotlin program.
 */
class ToolCallParserTest {
    private val jsonParser = Json { ignoreUnknownKeys = true }
    
    // Test the exact format from user's bug report
    fun testUserReportedFormat() {
        val input = """<|tool_call>call:tool_call{"name": "web_search", "arguments": {"query": "latest litelm-rt model"}}<tool_call|>"""
        
        println("=== Testing User Reported Format ===")
        println("Input: $input")
        println()
        
        val result = parseToolCalls(input)
        println("Parsed ${result.size} tool call(s):")
        result.forEach { tc ->
            println("  - name: ${tc.name}")
            println("    args: ${tc.arguments}")
        }
        println()
    }
    
    // Test various formats the model might output
    fun testVariousFormats() {
        val testCases = listOf(
            // Format 1: Original simple format
            "<tool_call>{\"name\": \"web_search\", \"arguments\": {\"query\": \"test\"}}</tool_call>",
            
            // Format 2: Gemma 4 format with call: prefix
            "<|tool_call>call:web_search{\"name\": \"web_search\", \"arguments\": {\"query\": \"hello\"}}<tool_call|>",
            
            // Format 3: User's exact bug report
            "<|tool_call>call:tool_call{\"name\": \"web_search\", \"arguments\": {\"query\": \"latest litelm-rt model\"}}<tool_call|>",
            
            // Format 4: With text before tool call
            "I'll search for that. <|tool_call>call:web_search{\"name\": \"web_search\", \"arguments\": {\"query\": \"weather\"}}<tool_call|>",
            
            // Format 5: Multiple tool calls
            """First search: <|tool_call>call:web_search{"name": "web_search", "arguments": {"query": "A"}}<tool_call|>
            Second search: <|tool_call>call:web_search{"name": "web_search", "arguments": {"query": "B"}}<tool_call|>""".trimIndent(),
            
            // Format 6: Nested quotes (potential edge case)
            "<|tool_call>call:web_search{\"name\": \"web_search\", \"arguments\": {\"query\": \"what's new\"}}<tool_call|>",
            
            // Format 7: No spaces in JSON
            "<|tool_call>call:web_search{\"name\":\"web_search\",\"arguments\":{\"query\":\"test\"}}<tool_call|>",
            
            // Format 8: Tool call in middle of sentence
            "Let me <|tool_call>call:web_search{\"name\": \"web_search\", \"arguments\": {\"query\": \"help\"}}<tool_call|> for you."
        )
        
        println("=== Testing Various Formats ===")
        testCases.forEachIndexed { index, input ->
            println("Test ${index + 1}:")
            println("  Input: ${input.take(80)}${if (input.length > 80) "..." else ""}")
            
            val result = parseToolCalls(input)
            if (result.isEmpty()) {
                println("  Result: FAILED - No tool calls parsed")
            } else {
                println("  Result: SUCCESS - ${result.size} tool call(s)")
                result.forEach { tc ->
                    println("    - ${tc.name}: ${tc.arguments}")
                }
            }
            println()
        }
    }
    
    // Simulate full agent flow
    fun simulateAgentFlow() {
        println("=== Simulating Agent Tool Calling Flow ===")
        
        val userInput = "What's the weather in Tokyo?"
        println("User: $userInput")
        println()
        
        // Simulate model response
        val modelResponse = """I'll check the weather for you.
        <|tool_call>call:web_search{"name": "web_search", "arguments": {"query": "Tokyo weather today"}}<tool_call|>""".trimIndent()
        
        println("Model raw output:")
        println(modelResponse)
        println()
        
        // Parse tool calls
        val toolCalls = parseToolCalls(modelResponse)
        println("Parsed ${toolCalls.size} tool call(s)")
        
        if (toolCalls.isNotEmpty()) {
            println("\nExecuting tools...")
            toolCalls.forEach { tc ->
                println("  Executing: ${tc.name} with args ${tc.arguments}")
                val result = executeDummyTool(tc.name, tc.arguments)
                println("  Result: $result")
            }
            
            println("\nFinal response would be generated with tool results in context")
        } else {
            println("ERROR: No tool calls found in model response!")
            println("This is the bug we need to fix.")
        }
    }
    
    // The actual parsing logic (copy of Agent.kt implementation)
    private fun parseToolCalls(response: String): List<ToolCall> {
        val toolCalls = mutableListOf<ToolCall>()
        var currentIndex = 0
        
        // Support multiple tool call formats
        val patterns = listOf(
            Pair("<|tool_call>call:", "<tool_call|>"),
            Pair("<tool_call>", "</tool_call>")
        )

        while (true) {
            // Find the next tool call using any of the supported patterns
            var foundPattern: Pair<String, String>? = null
            var startIndex = -1
            
            for ((startTag, endTag) in patterns) {
                val idx = response.indexOf(startTag, currentIndex)
                if (idx != -1 && (startIndex == -1 || idx < startIndex)) {
                    startIndex = idx
                    foundPattern = Pair(startTag, endTag)
                }
            }
            
            if (foundPattern == null || startIndex == -1) break
            
            val (startTag, endTag) = foundPattern
            val endIndex = response.indexOf(endTag, startIndex + startTag.length)
            if (endIndex == -1) break

            // Extract content between tags
            val contentStart = startIndex + startTag.length
            val content = response.substring(contentStart, endIndex).trim()

            try {
                // For <|tool_call>call: format, find where JSON starts
                val jsonStr = if (content.startsWith("tool_call")) {
                    // Skip "tool_call" prefix and find JSON
                    content.substring(content.indexOf("{"))
                } else if (content.startsWith("{")) {
                    // Already JSON
                    content
                } else {
                    // Try to find JSON anywhere in content
                    val jsonStart = content.indexOf("{")
                    if (jsonStart != -1) content.substring(jsonStart) else content
                }
                
                println("  [DEBUG] Parsing JSON: $jsonStr")
                
                val jsonObject = jsonParser.parseToJsonElement(jsonStr).jsonObject

                val name = jsonObject["name"]?.jsonPrimitive?.content ?: run {
                    println("  [DEBUG] Missing 'name' field in: $jsonStr")
                    currentIndex = endIndex + endTag.length
                    continue
                }
                
                val arguments = jsonObject["arguments"]?.jsonObject?.let { argsObj ->
                    argsObj.entries.associate { (k, v) ->
                        k to v.jsonPrimitive.content
                    }
                } ?: emptyMap()

                toolCalls.add(ToolCall(name = name, arguments = arguments))
                println("  [DEBUG] Successfully parsed: $name")
            } catch (e: Exception) {
                println("  [DEBUG] Failed to parse: $content - ${e.message}")
            }

            currentIndex = endIndex + endTag.length
        }

        return toolCalls
    }
    
    private fun executeDummyTool(name: String, arguments: Map<String, String>): String {
        return when (name) {
            "web_search" -> "Found results for: ${arguments["query"]}"
            "home_server" -> "Executed: ${arguments["command"]}"
            else -> "Unknown tool: $name"
        }
    }
    
    data class ToolCall(val name: String, val arguments: Map<String, String>)
}

// Main entry point
fun main() {
    val test = ToolCallParserTest()
    
    println("╔════════════════════════════════════════════════════════════╗")
    println("║     Sleepy Agent - Tool Call Parser Debug Harness          ║")
    println("╚════════════════════════════════════════════════════════════╝")
    println()
    
    test.testUserReportedFormat()
    test.testVariousFormats()
    test.simulateAgentFlow()
    
    println("\n=== Tests Complete ===")
}

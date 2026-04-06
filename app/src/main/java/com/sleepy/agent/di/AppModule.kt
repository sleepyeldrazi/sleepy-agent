package com.sleepy.agent.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.sleepy.agent.audio.AudioRecorder
import com.sleepy.agent.audio.AudioRecorderImpl
import com.sleepy.agent.audio.TtsService
import com.sleepy.agent.download.ModelDownloadManager
import com.sleepy.agent.inference.Agent
import com.sleepy.agent.inference.ConversationContext
import com.sleepy.agent.inference.LiteRtLlmEngine
import com.sleepy.agent.inference.LlmEngine
import com.sleepy.agent.settings.UserSettings
import com.sleepy.agent.tools.ServerTool
import com.sleepy.agent.tools.Tool
import com.sleepy.agent.tools.WebSearchTool
import com.sleepy.agent.ui.screens.MainViewModel
import com.sleepy.agent.ui.screens.SettingsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Manual Dependency Injection container.
 * Replaces Hilt/KSP for compatibility with LiteRT-LM's Kotlin 2.3.0 requirement.
 */
class AppModule(private val context: Context) {
    
    // Core
    val dataStore: DataStore<Preferences> by lazy { context.dataStore }
    
    // Settings
    val userSettings: UserSettings by lazy { UserSettings(dataStore) }
    
    // Network
    val ktorClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
            }
        }
    }
    
    // Audio
    val audioRecorder: AudioRecorder by lazy { AudioRecorderImpl(context) }
    val ttsService: TtsService by lazy { TtsService(context) }
    
    // LLM
    val llmEngine: LlmEngine by lazy { LiteRtLlmEngine(context) }
    val conversationContext: ConversationContext by lazy { ConversationContext() }
    
    // System prompt
    val systemPrompt: String by lazy {
        """You are a helpful AI assistant with access to tools.
        |
        |Available tools:
        |- web_search: Search the web for information
        |- home_server: Execute commands on the home server
        """.trimMargin()
    }
    
    // Tools
    val webSearchTool: WebSearchTool by lazy { WebSearchTool(ktorClient, "http://sleepy-think:7777") }
    val serverTool: ServerTool by lazy { ServerTool(ktorClient, "http://sleepy-think:8000") }
    
    // Download Manager
    val downloadManager: ModelDownloadManager by lazy { ModelDownloadManager(context) }
    
    val tools: Map<String, Tool> by lazy {
        mapOf(
            webSearchTool.name to webSearchTool,
            serverTool.name to serverTool
        )
    }
    
    // Agent
    val agent: Agent by lazy { Agent(llmEngine, conversationContext, tools) }
    
    fun createMainViewModel(savedStateHandle: androidx.lifecycle.SavedStateHandle): MainViewModel {
        return MainViewModel(
            savedStateHandle = savedStateHandle,
            context = context,
            audioRecorder = audioRecorder,
            ttsService = ttsService,
            agent = agent,
            llmEngine = llmEngine,
            userSettings = userSettings,
            webSearchTool = webSearchTool
        )
    }
    
    fun createSettingsViewModel(): SettingsViewModel {
        return SettingsViewModel(
            userSettings = userSettings,
            httpClient = ktorClient,
            llmEngine = llmEngine,
            context = context,
            downloadManager = downloadManager
        )
    }
}

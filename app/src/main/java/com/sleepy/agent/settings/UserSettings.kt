package com.sleepy.agent.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UserSettings(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        // Server URLs
        val SERVER_URL = stringPreferencesKey("server_url")
        val SEARCH_SERVER_URL = stringPreferencesKey("search_server_url")
        val DELEGATE_SERVER_URL = stringPreferencesKey("delegate_server_url")
        
        // Model settings
        val MODEL_PATH = stringPreferencesKey("model_path")
        val ENABLE_SERVER_DELEGATION = booleanPreferencesKey("enable_server_delegation")
        val MODEL_SOURCE = stringPreferencesKey("model_source")
        val SELECTED_SERVER_MODEL = stringPreferencesKey("selected_server_model")
        
        // TTS settings
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val TTS_AUTO_MODE = booleanPreferencesKey("tts_auto_mode")
        val TTS_PREFERRED_INPUT = stringPreferencesKey("tts_preferred_input")
        
        // Experimental features
        val FLOATING_BUTTON_ENABLED = booleanPreferencesKey("floating_button_enabled")
    }

    // Search server (for web search tool) - empty default, user must configure
    val searchServerUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[SEARCH_SERVER_URL] ?: ""
    }

    // Delegate server (for LLM inference) - empty default, user must configure  
    val delegateServerUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[DELEGATE_SERVER_URL] ?: ""
    }

    // Legacy combined URL
    val serverUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[SERVER_URL] ?: ""
    }

    val modelPath: Flow<String> = dataStore.data.map { prefs ->
        prefs[MODEL_PATH] ?: ""
    }

    val enableServerDelegation: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[ENABLE_SERVER_DELEGATION] ?: false
    }

    val modelSource: Flow<ModelSource> = dataStore.data.map { prefs ->
        prefs[MODEL_SOURCE]?.let { ModelSource.valueOf(it) } ?: ModelSource.FILE_PATH
    }

    val selectedServerModel: Flow<String> = dataStore.data.map { prefs ->
        prefs[SELECTED_SERVER_MODEL] ?: ""
    }
    
    // TTS settings
    val ttsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[TTS_ENABLED] ?: true // Default to enabled
    }
    
    val ttsAutoMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[TTS_AUTO_MODE] ?: true // Default to auto-detect
    }
    
    val ttsPreferredInput: Flow<String> = dataStore.data.map { prefs ->
        prefs[TTS_PREFERRED_INPUT] ?: "" // Empty = not set yet, "voice" or "text"
    }

    suspend fun setSearchServerUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[SEARCH_SERVER_URL] = url
        }
    }
    
    suspend fun setDelegateServerUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[DELEGATE_SERVER_URL] = url
        }
    }

    suspend fun setServerUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[SERVER_URL] = url
        }
    }

    suspend fun setModelPath(path: String) {
        dataStore.edit { prefs ->
            prefs[MODEL_PATH] = path
        }
    }

    suspend fun setEnableServerDelegation(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ENABLE_SERVER_DELEGATION] = enabled
        }
    }

    suspend fun setModelSource(source: ModelSource) {
        dataStore.edit { prefs ->
            prefs[MODEL_SOURCE] = source.name
        }
    }

    suspend fun setSelectedServerModel(model: String) {
        dataStore.edit { prefs ->
            prefs[SELECTED_SERVER_MODEL] = model
        }
    }
    
    suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[TTS_ENABLED] = enabled
        }
    }
    
    suspend fun setTtsAutoMode(auto: Boolean) {
        dataStore.edit { prefs ->
            prefs[TTS_AUTO_MODE] = auto
        }
    }
    
    suspend fun setTtsPreferredInput(input: String) {
        dataStore.edit { prefs ->
            prefs[TTS_PREFERRED_INPUT] = input
        }
    }
    
    // Floating button (experimental feature)
    val floatingButtonEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FLOATING_BUTTON_ENABLED] ?: false // Default off
    }
    
    suspend fun setFloatingButtonEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[FLOATING_BUTTON_ENABLED] = enabled
        }
    }
}

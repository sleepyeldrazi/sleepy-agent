package com.sleepy.agent.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object Preferences {
    val SERVER_URL = stringPreferencesKey("server_url")
    val MODEL_PATH = stringPreferencesKey("model_path")
    val ENABLE_SERVER_DELEGATION = booleanPreferencesKey("enable_server_delegation")
    val MODEL_SOURCE = stringPreferencesKey("model_source")
    val SELECTED_SERVER_MODEL = stringPreferencesKey("selected_server_model")
}

enum class ModelSource(val displayName: String, val description: String) {
    FILE_PATH("Local File", "Load a .litertlm model file from storage"),
    E2B("Gemma 4 E2B", "2B parameter model (fast, ~1.5GB)"),
    E4B("Gemma 4 E4B", "4B parameter model (capable, ~2.5GB)")
}

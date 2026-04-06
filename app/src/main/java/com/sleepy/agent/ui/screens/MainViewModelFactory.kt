package com.sleepy.agent.ui.screens

import android.content.Context
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner
import com.sleepy.agent.di.AppModule

class MainViewModelFactory(
    private val appModule: AppModule,
    private val context: Context,
    owner: SavedStateRegistryOwner
) : AbstractSavedStateViewModelFactory(owner, null) {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(
                savedStateHandle = handle,
                context = context,
                audioRecorder = appModule.audioRecorder,
                ttsService = appModule.ttsService,
                agent = appModule.agent,
                llmEngine = appModule.llmEngine,
                userSettings = appModule.userSettings,
                webSearchTool = appModule.webSearchTool
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

package com.sleepy.agent

import android.app.Application
import com.sleepy.agent.di.AppModule

class SleepyAgentApplication : Application() {
    
    lateinit var appModule: AppModule
        private set
    
    override fun onCreate() {
        super.onCreate()
        appModule = AppModule(this)
    }
    
    companion object {
        fun getAppModule(application: Application): AppModule {
            return (application as SleepyAgentApplication).appModule
        }
    }
}

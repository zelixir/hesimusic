package com.zjr.hesimusic

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class HesiMusicApplication : Application() {
    
    val lifecycleObserver = AppLifecycleObserver()
    
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }
}

/**
 * Observes the application's foreground/background state.
 * Used to optimize battery consumption by pausing UI updates when app is in background.
 */
class AppLifecycleObserver : DefaultLifecycleObserver {
    var isAppInForeground = false
        private set
    
    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground = true
    }
    
    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground = false
    }
}

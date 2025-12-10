package com.zjr.hesimusic

import android.app.Application
import com.zjr.hesimusic.utils.AppLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HesiMusicApplication : Application() {
    @Inject
    lateinit var appLogger: AppLogger
    
    override fun onCreate() {
        val startTime = System.currentTimeMillis()
        super.onCreate()
        
        // Log application startup
        val duration = System.currentTimeMillis() - startTime
        appLogger.timing("HesiMusicApplication", "Application onCreate", duration)
        appLogger.info("HesiMusicApplication", "HesiMusic application started")
    }
}

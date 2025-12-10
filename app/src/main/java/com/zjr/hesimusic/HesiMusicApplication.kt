package com.zjr.hesimusic

import android.app.Application
import android.util.Log
import com.zjr.hesimusic.utils.AppLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HesiMusicApplication : Application() {
    @Inject
    lateinit var appLogger: AppLogger
    
    private val TAG = "HesiMusicApplication"
    
    override fun onCreate() {
        val appStartTime = System.currentTimeMillis()
        Log.i(TAG, "Application onCreate started")
        
        // Measure super.onCreate() which includes Hilt initialization
        val hiltStartTime = System.currentTimeMillis()
        super.onCreate()
        val hiltDuration = System.currentTimeMillis() - hiltStartTime
        
        // Now we can use appLogger after Hilt initialization
        appLogger.info(TAG, "HesiMusic application onCreate started")
        appLogger.timing(TAG, "Hilt dependency injection initialization", hiltDuration)
        
        // Log total application startup time
        val totalDuration = System.currentTimeMillis() - appStartTime
        appLogger.timing(TAG, "Total application onCreate", totalDuration)
        appLogger.info(TAG, "HesiMusic application started successfully")
    }
}

package com.hesimusic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.atomic.AtomicLong

object UiHelper {
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val lastToastTime = AtomicLong(0)
    private const val MIN_INTERVAL_MS = 1500L // rate-limit to avoid flooding

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun showToast(message: String?) {
        val msg = message ?: "发生错误"
        val now = System.currentTimeMillis()
        val prev = lastToastTime.get()
        if (now - prev < MIN_INTERVAL_MS) return
        if (!lastToastTime.compareAndSet(prev, now)) return

        val ctx = appContext ?: return
        handler.post {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
                // best-effort
            }
        }
    }
}

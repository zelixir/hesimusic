package zelixir.hesimusic.scan

import android.content.Context
import java.io.File

object ScanLogger {
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    fun logInfo(msg: String) {
        write("INFO", msg)
    }

    fun logError(e: Throwable, contextMsg: String) {
        write("ERROR", "$contextMsg: ${e.message}")
    }

    private fun write(level: String, msg: String) {
        try {
            val c = ctx ?: return
            val dir = File(c.filesDir, "scan_logs")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "scan.log")
            val line = "${System.currentTimeMillis()} [$level] $msg\n"
            f.appendText(line)
        } catch (_: Throwable) {}
    }
}

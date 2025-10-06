package zelixir.hesimusic.scan

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts

data class PermissionResult(val granted: Boolean, val requiresSaf: Boolean)

object PermissionHelper {
    fun ensureStoragePermissions(activity: Activity): PermissionResult {
        // Basic check: for Android 11+ recommend using SAF/MediaStore. Here we provide a simple fc check.
        val granted = true // In real app, check Manifest permissions and runtime permissions.
        val requiresSaf = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        return PermissionResult(granted = granted, requiresSaf = requiresSaf)
    }

    fun requestSafAccess(activity: Activity, uri: Uri): Boolean {
        try {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            return true
        } catch (_: Throwable) {
            return false
        }
    }
}

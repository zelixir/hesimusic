package zelixir.hesimusic.scan

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts

data class PermissionResult(val granted: Boolean, val requiresSaf: Boolean)

object PermissionHelper {
    fun ensureStoragePermissions(activity: Activity): PermissionResult {
        // Basic runtime permission check. This is non-blocking and returns whether permissions are
        // already granted and whether SAF (MANAGE_EXTERNAL_STORAGE / document tree) is recommended.
        val requiresSaf = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        // For older Android versions check READ_EXTERNAL_STORAGE runtime permission
    var granted: Boolean
        try {
            if (!requiresSaf) {
                val perm = android.Manifest.permission.READ_EXTERNAL_STORAGE
                val pm = androidx.core.content.ContextCompat.checkSelfPermission(activity, perm)
                granted = pm == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                // On Android 11+ apps should use MediaStore or SAF. We conservatively report false here
                // so the caller can initiate SAF flow if needed.
                // If the app targets older behavior or has MANAGE_EXTERNAL_STORAGE permission, callers
                // can detect and proceed differently.
                granted = false
            }
        } catch (e: Throwable) {
            try { ScanManager.reportError(null, "permission check failed: ${e.message}") } catch (_: Throwable) {}
            granted = false
        }

        return PermissionResult(granted = granted, requiresSaf = requiresSaf)
    }

    fun requestSafAccess(activity: Activity, uri: Uri): Boolean {
        try {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            return true
        } catch (e: Throwable) {
            try { ScanManager.reportError(null, "无法保存目录授权: ${e.message}") } catch (_: Throwable) {}
            return false
        }
    }
}

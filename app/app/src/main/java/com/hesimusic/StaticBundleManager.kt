package com.hesimusic

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class StaticBundleManager(private val context: Context) {
    private val assetsName = "frontend.zip"
    private val frontendDirName = "frontend"
    private val hashFile = File(context.filesDir, "frontend.bundlehash")

    fun getFrontendDir(): File = File(context.filesDir, frontendDirName)

    /**
     * Ensure the bundle extracted on disk matches the bundled asset. If the asset has changed,
     * unpack to a temporary directory, verify and atomically swap it into place. Returns true
     * when an update was applied (caller may refresh UI). Returns false when nothing changed or
     * on recoverable failure.
     */
    fun ensureBundleUpToDate(): Boolean {
        val assetHash = computeAssetHash() ?: return false
        val currentHash = readSavedHash()
        val frontendDir = getFrontendDir()

        // If hash unchanged and frontend dir exists, nothing to do
        if (assetHash == currentHash && frontendDir.exists()) return false

        // Unpack into a temp dir
        val tempDir = File(context.filesDir, "${frontendDirName}_tmp_${System.currentTimeMillis()}")
        try {
            unpackAssetToDir(tempDir)
            if (!verifyBundle(tempDir)) {
                Log.w("HesiMusic", "Bundle verification failed: index.html missing")
                tempDir.deleteRecursively()
                return false
            }

            // Attempt atomic swap: move existing -> backup, move temp -> frontend
            val backupDir = File(context.filesDir, "${frontendDirName}_bak_${System.currentTimeMillis()}")
            if (frontendDir.exists()) {
                val renamed = frontendDir.renameTo(backupDir)
                if (!renamed) {
                    // Fallback: try copy then delete
                    try {
                        backupDir.deleteRecursively()
                        frontendDir.copyRecursively(backupDir, overwrite = true)
                        frontendDir.deleteRecursively()
                    } catch (e: Exception) {
                        Log.w("HesiMusic", "Failed to backup existing frontend: ${e.message}")
                        tempDir.deleteRecursively()
                        return false
                    }
                }
            }

            val swapped = tempDir.renameTo(frontendDir)
            if (!swapped) {
                // try copy as last resort
                try {
                    frontendDir.deleteRecursively()
                    tempDir.copyRecursively(frontendDir, overwrite = true)
                    tempDir.deleteRecursively()
                } catch (e: Exception) {
                    Log.w("HesiMusic", "Failed to move new frontend into place: ${e.message}")
                    // Attempt rollback
                    if (backupDir.exists()) {
                        backupDir.renameTo(frontendDir)
                    }
                    tempDir.deleteRecursively()
                    return false
                }
            }

            // Persist new hash atomically
            try {
                val tmpHash = File(context.filesDir, "frontend.bundlehash.tmp")
                FileOutputStream(tmpHash).use { it.write(assetHash.toByteArray(Charsets.UTF_8)) }
                tmpHash.renameTo(hashFile)
            } catch (e: Exception) {
                Log.w("HesiMusic", "Failed to persist bundle hash: ${e.message}")
            }

            // Remove backup
            try {
                if (backupDir.exists()) backupDir.deleteRecursively()
            } catch (ignored: Exception) {}

            Log.i("HesiMusic", "Frontend bundle updated (hash=$assetHash)")
            return true
        } catch (e: Exception) {
            Log.w("HesiMusic", "Failed to unpack frontend bundle: ${e.message}")
            tempDir.deleteRecursively()
            return false
        }
    }

    private fun computeAssetHash(): String? {
        return try {
            context.assets.open(assetsName).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
                bytesToHex(digest.digest())
            }
        } catch (e: Exception) {
            Log.w("HesiMusic", "Failed to read asset $assetsName: ${e.message}")
            null
        }
    }

    private fun readSavedHash(): String? {
        return try {
            if (!hashFile.exists()) return null
            hashFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun unpackAssetToDir(targetDir: File) {
        targetDir.mkdirs()
        context.assets.open(assetsName).use { inputStream ->
            ZipInputStream(inputStream).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zipIn.copyTo(out)
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }
    }

    private fun verifyBundle(dir: File): Boolean {
        val index = File(dir, "index.html")
        return index.exists() && index.isFile
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}

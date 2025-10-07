package zelixir.hesimusic.scan

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Semaphore

/**
 * Lightweight FileScanner implementation based on BFS + coroutine worker pool.
 * Produces candidate files via callback and supports pause/resume with a serializable cursor.
 */
class FileScanner(private val coroutineScope: ScanWorker) {
    private val _mutex = Mutex()
    private var paused = false
    private val pauseLock = Mutex(locked = false)

    private val dirSemaphore = Semaphore(4) // limit concurrent directory listings

    data class ScanCursor(val pendingPaths: List<String>, val processedCount: Int)

    suspend fun scanRoots(
        roots: List<File>,
        options: ScanOptions,
        onFile: suspend (File) -> Unit,
        cursor: ScanCursor? = null,
        onProgress: ((processed: Int, currentPath: String?) -> Unit)? = null
    ): ScanCursor {
        return withContext(Dispatchers.IO) {
            val queue = ArrayDeque<String>()
            val processedCount = intArrayOf(0)

            // initialize queue from cursor or roots
            if (cursor != null && cursor.pendingPaths.isNotEmpty()) {
                cursor.pendingPaths.forEach { queue.add(it) }
            } else {
                roots.forEach { queue.add(it.absolutePath) }
            }

            val exclusion = buildExclusionMatcher(options)

            while (queue.isNotEmpty()) {
                ensureActive()

                // pause handling
                if (paused) {
                    pauseLock.withLock { /* block until resumed */ }
                }

                val path = queue.removeFirst()
                val f = File(path)
                val currentPath = f.absolutePath

                try {
                    if (!f.exists()) continue
                    if (f.isDirectory) {
                        // control concurrency for listing
                        dirSemaphore.acquire()
                        try {
                            val children = f.listFiles()
                            if (children != null) {
                                for (c in children) {
                                    if (exclusion(c)) continue
                                    queue.addLast(c.absolutePath)
                                }
                            }
                        } finally {
                            dirSemaphore.release()
                        }
                    } else if (f.isFile) {
                        // candidate file
                        onFile(f)
                        processedCount[0]++
                        onProgress?.invoke(processedCount[0], currentPath)
                    }
                } catch (t: Throwable) {
                    // report per-file errors to ScanManager for aggregation
                    try { ScanManager.reportError(null, t.message ?: t.toString()) } catch (_: Throwable) {}
                }
            }

            ScanCursor(pendingPaths = emptyList(), processedCount = processedCount[0])
        }
    }

    fun buildExclusionMatcher(options: ScanOptions): (File) -> Boolean {
        val excluded = options.excludedPaths.map { File(it).absolutePath }
        return { file ->
            // exclude if path startsWith any excluded path
            val p = file.absolutePath
            excluded.any { p.startsWith(it) } || file.name.startsWith('.')
        }
    }

    companion object {
        fun serializeCursor(cursor: ScanCursor): ByteArray {
            val sb = StringBuilder()
            sb.append(cursor.processedCount).append('\n')
            cursor.pendingPaths.forEach { sb.append(it.replace('\n', ' ')).append('\n') }
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        fun deserializeCursor(bytes: ByteArray): ScanCursor {
            val txt = bytes.toString(Charsets.UTF_8)
            val lines = txt.split('\n')
            val processed = lines.getOrNull(0)?.toIntOrNull() ?: 0
            val pending = if (lines.size > 1) lines.subList(1, lines.size).filter { it.isNotBlank() } else emptyList()
            return ScanCursor(pendingPaths = pending, processedCount = processed)
        }
    }
}

package com.zjr.hesimusic.data.scanner

import android.os.Environment
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.utils.AlphabetIndexer
import java.io.File
import javax.inject.Inject

class FileScanner @Inject constructor(
    private val cueParser: CueParser,
    private val tagLibHelper: TagLibHelper
) {
    private val supportedExtensions = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg")

    suspend fun scan(
        scanFolders: Set<String> = emptySet(),
        excludedFolders: Set<String> = emptySet(),
        onProgress: suspend (String, Int) -> Unit
    ): List<Song> {
        val songs = mutableListOf<Song>()
        val cueReferencedFiles = mutableSetOf<String>() // Absolute paths

        // Determine root directories to scan
        val rootDirectories = if (scanFolders.isEmpty()) {
            listOf(Environment.getExternalStorageDirectory())
        } else {
            scanFolders.map { File(it) }.filter { it.exists() && it.isDirectory }
        }

        // 1. Find all CUE files first
        val cueFiles = mutableListOf<File>()
        val audioFiles = mutableListOf<File>()

        for (rootDir in rootDirectories) {
            scanDirectory(rootDir, cueFiles, audioFiles, songs.size, excludedFolders, onProgress)
        }

        // 2. Process CUE files
        cueFiles.forEach { cueFile ->
            onProgress(cueFile.parent ?: cueFile.absolutePath, songs.size)
            val tracks = cueParser.parse(cueFile)
            if (tracks.isNotEmpty()) {
                // Group by file name to handle multiple files in one CUE
                val tracksByFile = tracks.groupBy { it.fileName }
                
                tracksByFile.forEach { (fileName, fileTracks) ->
                    val audioFile = File(cueFile.parentFile, fileName)
                    if (audioFile.exists()) {
                        cueReferencedFiles.add(audioFile.absolutePath)
                        
                        // Get audio file metadata for duration etc.
                        val metadata = tagLibHelper.extractMetadata(audioFile.absolutePath)
                        
                        val totalDuration = metadata?.get("DURATION")?.toLongOrNull() ?: 0L
                        val mimeType = "audio/${audioFile.extension}"

                        fileTracks.forEachIndexed { index, track ->
                            val nextTrack = fileTracks.getOrNull(index + 1)
                            val endMs = nextTrack?.startMs ?: -1L // -1 means end of file

                            songs.add(Song(
                                title = track.title,
                                artist = track.performer,
                                album = track.album,
                                filePath = audioFile.absolutePath,
                                duration = if (endMs != -1L) endMs - track.startMs else totalDuration - track.startMs,
                                trackNumber = track.trackNumber,
                                mimeType = mimeType,
                                size = 0, // Virtual size
                                dateAdded = System.currentTimeMillis(),
                                isCue = true,
                                cueFilePath = cueFile.absolutePath,
                                startPosition = track.startMs,
                                endPosition = endMs,
                                titleInitial = AlphabetIndexer.getInitial(track.title).toString(),
                                folderPath = audioFile.parent ?: ""
                            ))
                            onProgress(cueFile.parent ?: cueFile.absolutePath, songs.size)
                        }
                    }
                }
            }
        }

        // 3. Process regular audio files
        audioFiles.forEach { file ->
            onProgress(file.parent ?: file.absolutePath, songs.size)
            if (!cueReferencedFiles.contains(file.absolutePath)) {
                try {
                    val metadata = tagLibHelper.extractMetadata(file.absolutePath)

                    if (metadata != null) {
                        val title = cleanTag(metadata["TITLE"])
                        val artist = cleanTag(metadata["ARTIST"])
                        val album = cleanTag(metadata["ALBUM"])
                        val duration = metadata["DURATION"]?.toDoubleOrNull()?.toLong() ?: 0L // TagLib returns ms as string from double? No, I cast to long in C++ but let's be safe. C++ code: std::to_string(properties->length() * 1000) which is int/long.
                        val finalTitle = title.ifEmpty { file.nameWithoutExtension }

                        songs.add(Song(
                            title = finalTitle,
                            artist = artist.ifEmpty { "Unknown Artist" },
                            album = album.ifEmpty { "Unknown Album" },
                            filePath = file.absolutePath,
                            duration = duration,
                            trackNumber = metadata["TRACK"]?.toIntOrNull() ?: 0,
                            year = cleanTag(metadata["YEAR"]),
                            genre = cleanTag(metadata["GENRE"]),
                            mimeType = "audio/${file.extension}",
                            size = file.length(),
                            dateAdded = file.lastModified(),
                            titleInitial = AlphabetIndexer.getInitial(finalTitle).toString(),
                            folderPath = file.parent ?: ""
                        ))
                    } else {
                         val fallbackTitle = file.nameWithoutExtension
                         songs.add(Song(
                            title = fallbackTitle,
                            artist = "Unknown Artist",
                            album = "Unknown Album",
                            filePath = file.absolutePath,
                            duration = 0,
                            trackNumber = 0,
                            mimeType = "audio/${file.extension}",
                            size = file.length(),
                            dateAdded = file.lastModified(),
                            titleInitial = AlphabetIndexer.getInitial(fallbackTitle).toString(),
                            folderPath = file.parent ?: ""
                        ))
                    }
                    onProgress(file.parent ?: file.absolutePath, songs.size)
                } catch (e: Exception) {
                    // Log error or skip
                    e.printStackTrace()
                }
            }
        }

        return songs
    }

    private suspend fun scanDirectory(
        dir: File,
        cueFiles: MutableList<File>,
        audioFiles: MutableList<File>,
        currentSongCount: Int,
        excludedFolders: Set<String>,
        onProgress: suspend (String, Int) -> Unit
    ) {
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                // Check if this directory should be excluded
                val isExcluded = excludedFolders.any { excluded ->
                    file.absolutePath == excluded || file.absolutePath.startsWith("$excluded/")
                }
                
                val shouldEnter = !isExcluded &&
                    !file.path.contains("/Recordings/", ignoreCase = true) && 
                    !file.path.contains("/sound_recorder/")
                    
                if (shouldEnter) {
                    onProgress(file.absolutePath, currentSongCount)
                    scanDirectory(file, cueFiles, audioFiles, currentSongCount, excludedFolders, onProgress)
                }
            } else {
                val ext = file.extension.lowercase()
                if (ext == "cue") {
                    cueFiles.add(file)
                } else if (supportedExtensions.contains(ext)) {
                    audioFiles.add(file)
                }
            }
        }
    }

    private fun cleanTag(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        var res = value
        if (res.startsWith("\uFEFF")) {
            res = res.substring(1)
        }
        return res.trim()
    }
}

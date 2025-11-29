package com.zjr.hesimusic.data.scanner

import android.os.Environment
import com.zjr.hesimusic.data.model.Song
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import javax.inject.Inject

class FileScanner @Inject constructor(
    private val cueParser: CueParser
) {
    private val supportedExtensions = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg")

    fun scan(): List<Song> {
        val root = Environment.getExternalStorageDirectory()
        val songs = mutableListOf<Song>()
        val cueReferencedFiles = mutableSetOf<String>() // Absolute paths

        // 1. Find all CUE files first
        val cueFiles = mutableListOf<File>()
        val audioFiles = mutableListOf<File>()

        root.walkTopDown().forEach { file ->
            if (file.isFile) {
                val ext = file.extension.lowercase()
                if (ext == "cue") {
                    cueFiles.add(file)
                } else if (supportedExtensions.contains(ext)) {
                    audioFiles.add(file)
                }
            }
        }

        // 2. Process CUE files
        cueFiles.forEach { cueFile ->
            val tracks = cueParser.parse(cueFile)
            if (tracks.isNotEmpty()) {
                // Group by file name to handle multiple files in one CUE
                val tracksByFile = tracks.groupBy { it.fileName }
                
                tracksByFile.forEach { (fileName, fileTracks) ->
                    val audioFile = File(cueFile.parentFile, fileName)
                    if (audioFile.exists()) {
                        cueReferencedFiles.add(audioFile.absolutePath)
                        
                        // Get audio file metadata for duration etc.
                        val audioHeader = try {
                            AudioFileIO.read(audioFile).audioHeader
                        } catch (e: Exception) { null }
                        
                        val totalDuration = audioHeader?.trackLength?.toLong()?.times(1000) ?: 0L
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
                                endPosition = endMs
                            ))
                        }
                    }
                }
            }
        }

        // 3. Process regular audio files
        audioFiles.forEach { file ->
            if (!cueReferencedFiles.contains(file.absolutePath)) {
                try {
                    val audioFile = AudioFileIO.read(file)
                    val tag = audioFile.tag
                    val header = audioFile.audioHeader

                    songs.add(Song(
                        title = tag?.getFirst(FieldKey.TITLE) ?: file.nameWithoutExtension,
                        artist = tag?.getFirst(FieldKey.ARTIST) ?: "Unknown Artist",
                        album = tag?.getFirst(FieldKey.ALBUM) ?: "Unknown Album",
                        filePath = file.absolutePath,
                        duration = header.trackLength.toLong() * 1000,
                        trackNumber = tag?.getFirst(FieldKey.TRACK)?.toIntOrNull() ?: 0,
                        year = tag?.getFirst(FieldKey.YEAR),
                        genre = tag?.getFirst(FieldKey.GENRE),
                        mimeType = "audio/${file.extension}",
                        size = file.length(),
                        dateAdded = file.lastModified()
                    ))
                } catch (e: Exception) {
                    // Log error or skip
                    e.printStackTrace()
                }
            }
        }

        return songs
    }
}

package com.zjr.hesimusic.data.mapper

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.zjr.hesimusic.data.model.Song

fun Song.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        // Note: Artwork URI handling might need adjustment based on how we want to load images.
        // For now, we don't set a specific URI here unless we have one.
        // MediaSession might try to load it if we provide one.
        .build()

    // For CUE tracks, filePath points to the actual audio file (e.g. .flac, .wav)
    // cueFilePath points to the .cue file itself, which we don't want to play directly.
    val uriString = filePath
    
    // Ensure URI is valid. If it's a file path, we might need to prepend "file://" or use Uri.fromFile
    // But usually ExoPlayer handles paths. Best to use Uri.parse properly.
    val uri = if (uriString.startsWith("/")) "file://$uriString" else uriString

    val builder = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(metadata)
        .setMimeType(mimeType)

    if (isCue) {
        val clippingConfig = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startPosition)
        
        if (endPosition != -1L) {
            clippingConfig.setEndPositionMs(endPosition)
        }
        
        builder.setClippingConfiguration(clippingConfig.build())
    }

    return builder.build()
}

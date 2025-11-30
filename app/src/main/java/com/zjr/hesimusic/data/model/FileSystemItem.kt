package com.zjr.hesimusic.data.model

sealed class FileSystemItem {
    data class Folder(val name: String, val path: String, val songCount: Int) : FileSystemItem()
    data class MusicFile(val song: Song) : FileSystemItem()
}

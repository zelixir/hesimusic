package com.zjr.hesimusic.data.mapper

// Songs appended to the playback queue get a unique mediaId so queue bookkeeping can
// tell a queued copy apart from base-playlist copies of the same song (e.g. the
// currently playing item queued to play again). Format: "queue:<sequence>:<songId>".
private const val QUEUE_MEDIA_ID_PREFIX = "queue:"

fun buildQueueMediaId(sequence: Long, songId: Long): String =
    "$QUEUE_MEDIA_ID_PREFIX$sequence:$songId"

fun isQueueMediaId(mediaId: String): Boolean = mediaId.startsWith(QUEUE_MEDIA_ID_PREFIX)

/** Song id behind a mediaId; works for both plain ids and queued-copy ids. */
fun mediaIdToSongId(mediaId: String): Long? {
    if (!isQueueMediaId(mediaId)) return mediaId.toLongOrNull()
    return mediaId.removePrefix(QUEUE_MEDIA_ID_PREFIX).substringAfterLast(':').toLongOrNull()
}

package com.zjr.spike

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoopingMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import java.io.File

/**
 * P0 探针入口：纯 android.app.Activity（无 Hilt / Compose）。
 *
 * intent extras 与行为见 spike/CONTRACT.md §2；核心取证在 TapAudioSink / SpikeRecorder。
 * 任何异常都必须落 error 事件（含 stack）后再结束，不得静默退出。
 */
@UnstableApi
class SpikeActivity : Activity() {

    private lateinit var cfg: SpikeConfig
    private lateinit var rec: SpikeRecorder
    private lateinit var probe: TrackProbeProvider

    private var tapSink: TapAudioSink? = null
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var finishing = false
    private var seekDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            setupAndRun()
        } catch (t: Throwable) {
            fatal(t)
        }
    }

    // ------------------------------------------------------------------ setup

    private fun setupAndRun() {
        cfg = parseConfig()
        SpikeRun.tapEnabled = cfg.tap
        rec = SpikeRecorder(File(filesDir, "spike"))
        rec.event(
            "run",
            cfg.toMap() + linkedMapOf(
                "sdk" to Build.VERSION.SDK_INT,
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "pid" to Process.myPid(),
            ),
        )
        Log.i(SpikeRecorder.TAG, "run mode=" + cfg.mode + " path=" + cfg.path + " tag=" + cfg.tag)

        val file = File(cfg.path)
        if (!file.exists()) {
            rec.event("error", linkedMapOf("phase" to "open", "message" to "file not found: " + cfg.path))
            finishRun("file-not-found")
            return
        }

        probe = TrackProbeProvider(rec)
        val defaultSink = DefaultAudioSink.Builder(this)
            .setAudioTrackProvider(probe)
            .build()
        val sink = TapAudioSink(defaultSink, rec, cfg, probe)
        tapSink = sink
        val renderersFactory = TapRenderersFactory(this, sink)
        // 与主 app 的 di/MediaModule.kt 完全对齐，否则测到的不是 app 真实行为：
        //  - DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)（MP3 seek 精度）
        //  - DefaultMediaSourceFactory(context, extractorsFactory)
        //  - AudioAttributes(USAGE_MEDIA / CONTENT_TYPE_MUSIC) + handleAudioFocus + handleAudioBecomingNoisy
        val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        p.addListener(playerListener)

        val uri = Uri.fromFile(file)
        when (cfg.mode) {
            "plain" -> {
                p.setMediaItem(MediaItem.fromUri(uri))
                rec.event(
                    "mode_setup",
                    linkedMapOf("mode" to "plain", "items" to 1, "repeatMode" to "off"),
                )
            }
            "baseline" -> {
                val first = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs(cfg.bMs.toLong())
                            .build(),
                    )
                    .build()
                val loopClip = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(cfg.aMs.toLong())
                            .setEndPositionMs(cfg.bMs.toLong())
                            .build(),
                    )
                    .build()
                p.setMediaItems(listOf(first, loopClip), 1, 0L)
                p.repeatMode = Player.REPEAT_MODE_ONE
                rec.event(
                    "mode_setup",
                    linkedMapOf(
                        "mode" to "baseline",
                        "items" to 2,
                        "startIndex" to 1,
                        "item0" to "[0," + cfg.bMs + ")ms",
                        "item1" to "[" + cfg.aMs + "," + cfg.bMs + ")ms",
                        "repeatMode" to "one",
                    ),
                )
            }
            "window" -> {
                val progressive = mediaSourceFactory.createMediaSource(MediaItem.fromUri(uri))
                val clipped = ClippingMediaSource(progressive, cfg.aUs, cfg.bUs)
                val looping = LoopingMediaSource(clipped, cfg.loops)
                p.setMediaSource(looping)
                p.repeatMode = Player.REPEAT_MODE_OFF
                rec.event(
                    "mode_setup",
                    linkedMapOf(
                        "mode" to "window",
                        "loops" to cfg.loops,
                        "window" to "[" + cfg.aMs + "," + cfg.bMs + ")ms",
                        "repeatMode" to "off",
                    ),
                )
            }
            "sink" -> {
                val clip = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs(cfg.bMs.toLong())
                            .build(),
                    )
                    .build()
                p.setMediaItem(clip)
                rec.event(
                    "mode_setup",
                    linkedMapOf(
                        "mode" to "sink",
                        "clip" to "[0," + cfg.bMs + ")ms",
                        "capture" to "[" + cfg.aMs + "," + cfg.bMs + ")ms",
                    ),
                )
            }
            else -> {
                rec.event(
                    "error",
                    linkedMapOf(
                        "phase" to "mode",
                        "message" to ("mode not implemented or unknown: " + cfg.mode),
                    ),
                )
                finishRun("mode-unimplemented")
                return
            }
        }

        p.prepare()
        rec.event("prepared")
        p.play()
        rec.markPlayStart()
        handler.postDelayed({ finishRun("duration") }, cfg.seconds.toLong() * 1000L)
        if (cfg.seekToMs > 0) {
            handler.postDelayed({ doSeek() }, cfg.seekDelayMs.toLong())
        }
    }

    private fun doSeek() {
        val p = player ?: return
        if (seekDone || finishing) return
        seekDone = true
        val from = runCatching { p.currentPosition }.getOrDefault(-1L)
        rec.event("seek_request", linkedMapOf("toMs" to cfg.seekToMs, "fromPosMs" to from))
        p.seekTo(cfg.seekToMs.toLong())
    }

    // --------------------------------------------------------------- listeners

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            rec.event(
                "playback_state",
                linkedMapOf(
                    "state" to playbackStateName(playbackState),
                    "posMs" to runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L),
                    "itemIndex" to (player?.currentMediaItemIndex ?: -1),
                ),
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            rec.event(
                "is_playing",
                linkedMapOf(
                    "on" to isPlaying,
                    "posMs" to runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L),
                ),
            )
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            rec.event(
                "position_discontinuity",
                linkedMapOf(
                    "reason" to discontinuityName(reason),
                    "oldMs" to oldPosition.positionMs,
                    "newMs" to newPosition.positionMs,
                    "oldItem" to oldPosition.mediaItemIndex,
                    "newItem" to newPosition.mediaItemIndex,
                ),
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            rec.event(
                "media_item_transition",
                linkedMapOf(
                    "reason" to mediaItemTransitionName(reason),
                    "itemIndex" to (player?.currentMediaItemIndex ?: -1),
                    "itemCount" to (player?.mediaItemCount ?: -1),
                ),
            )
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            rec.event("repeat_mode", linkedMapOf("repeatMode" to repeatMode))
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            rec.event(
                "timeline",
                linkedMapOf(
                    "reason" to reason,
                    "windowCount" to timeline.windowCount,
                    "periodCount" to timeline.periodCount,
                ),
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            rec.event(
                "error",
                linkedMapOf(
                    "phase" to "player",
                    "message" to (error.message ?: ""),
                    "errorCodeName" to error.errorCodeName,
                    "stack" to Log.getStackTraceString(error),
                ),
            )
            handler.postDelayed({ finishRun("player-error") }, 300L)
        }
    }

    // ------------------------------------------------------------ finish / end

    private fun finishRun(reason: String) {
        if (finishing) return
        finishing = true
        handler.removeCallbacksAndMessages(null)
        rec.markStop()

        val p = player
        val pos = runCatching { p?.currentPosition ?: -1L }.getOrDefault(-1L)
        val ended = runCatching { tapSink?.probeEnded() ?: false }.getOrDefault(false)
        val pending = runCatching { tapSink?.probePending() ?: false }.getOrDefault(false)
        rec.event(
            "stop",
            linkedMapOf(
                "reason" to reason,
                "posMs" to pos,
                "sinkEnded" to ended,
                "sinkPending" to pending,
                "replayActive" to (tapSink?.replayIsActive() ?: false),
                "totalFrames" to rec.totalFrames,
            ),
        )
        tapSink?.stopReplay()
        runCatching { p?.pause() }
        runCatching { p?.release() }
        player = null

        try {
            rec.finishWav()
            rec.event(
                "end",
                linkedMapOf(
                    "totalFrames" to rec.totalFrames,
                    "durationMs" to rec.nowMs(),
                    "wavBytes" to rec.wavWrites,
                    "reason" to reason,
                ),
            )
            Log.i(SpikeRecorder.TAG, "end frames=" + rec.totalFrames + " reason=" + reason)
        } catch (t: Throwable) {
            Log.e(SpikeRecorder.TAG, "finalize failed", t)
        }
        rec.closeIfNeeded()
        if (!isFinishing) finish()
    }

    private fun fatal(t: Throwable) {
        Log.e(SpikeRecorder.TAG, "fatal", t)
        try {
            if (this::rec.isInitialized) {
                rec.event(
                    "error",
                    linkedMapOf(
                        "phase" to "fatal",
                        "message" to (t.message ?: ""),
                        "stack" to t.stackTraceToString(),
                    ),
                )
                rec.finishWav()
                rec.event("end", linkedMapOf("totalFrames" to rec.totalFrames, "reason" to "fatal"))
                rec.closeIfNeeded()
            }
        } catch (_: Throwable) {
        }
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!finishing && this::rec.isInitialized) {
            finishing = true
            tapSink?.stopReplay()
            runCatching { player?.release() }
            runCatching { rec.finishWav() }
            runCatching {
                rec.event("end", linkedMapOf("totalFrames" to rec.totalFrames, "reason" to "destroy"))
            }
            rec.closeIfNeeded()
        }
        super.onDestroy()
    }

    // --------------------------------------------------------------- extras

    private fun parseConfig(): SpikeConfig {
        val extras = intent?.extras
        fun str(name: String, def: String): String =
            extras?.get(name)?.toString()?.takeIf { it.isNotEmpty() } ?: def

        fun int(name: String, def: Int): Int {
            val v = extras?.get(name) ?: return def
            return when (v) {
                is Int -> v
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull() ?: def
                else -> def
            }
        }

        fun bool(name: String, def: Boolean): Boolean {
            val v = extras?.get(name) ?: return def
            return when (v) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> v == "1" || v.equals("true", ignoreCase = true)
                else -> def
            }
        }

        return SpikeConfig(
            mode = str("mode", "plain").trim(),
            path = str("path", ""),
            aMs = int("aMs", 20000),
            bMs = int("bMs", 50000),
            seconds = int("seconds", 25),
            loops = int("loops", 100),
            tap = bool("tap", true),
            seekToMs = int("seekToMs", -1),
            seekDelayMs = int("seekDelayMs", 1000),
            tag = str("tag", "default"),
        )
    }

    // --------------------------------------------------------------- names

    private fun playbackStateName(s: Int): String = when (s) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "state:" + s
    }

    private fun discontinuityName(r: Int): String = when (r) {
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "auto_transition"
        Player.DISCONTINUITY_REASON_SEEK -> "seek"
        Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "seek_adjustment"
        Player.DISCONTINUITY_REASON_SKIP -> "skip"
        Player.DISCONTINUITY_REASON_REMOVE -> "remove"
        Player.DISCONTINUITY_REASON_INTERNAL -> "internal"
        Player.DISCONTINUITY_REASON_SILENCE_SKIP -> "silence_skip"
        else -> "reason:" + r
    }

    private fun mediaItemTransitionName(r: Int): String = when (r) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist_changed"
        else -> "reason:" + r
    }
}
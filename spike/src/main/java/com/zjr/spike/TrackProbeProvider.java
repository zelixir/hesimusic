package com.zjr.spike;

import android.content.Context;
import android.media.AudioTrack;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/**
 * 自定义 AudioTrackProvider：拿到底层 AudioTrack 引用，每 500ms 采样 underrun 与 playbackHeadPosition。
 * 创建走 media3 默认实现（行为不变），只是留个引用。
 *
 * 用 Java 写是因为 Kotlin(K2) 对该 interface 的 @Nullable 参数判定为“overrides nothing”。
 */
@UnstableApi
public final class TrackProbeProvider implements DefaultAudioSink.AudioTrackProvider {

    private final SpikeRecorder rec;
    private volatile AudioTrack track;

    private volatile boolean sampling;
    private volatile Thread sampler;
    private volatile int sampleRate = 48000;

    public TrackProbeProvider(SpikeRecorder rec) {
        this.rec = rec;
    }
    public AudioTrack getTrack() {
        return track;
    }

    @Override
    public AudioTrack getAudioTrack(
            AudioSink.AudioTrackConfig config,
            AudioAttributes audioAttributes,
            int streamType,
            Context context) {
        AudioTrack audioTrack = DefaultAudioSink.AudioTrackProvider.DEFAULT
                .getAudioTrack(config, audioAttributes, streamType, context);
        track = audioTrack;
        sampleRate = config.sampleRate;
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("sampleRate", config.sampleRate);
        fields.put("encoding", config.encoding);
        fields.put("channelConfig", config.channelConfig);
        fields.put("bufferSize", config.bufferSize);
        fields.put("state", audioTrack.getState());
        rec.event("track_new", fields);
        startSampling();
        return audioTrack;
    }

    private void startSampling() {
        if (sampling) {
            return;
        }
        sampling = true;
        Thread t = new Thread(() -> {
            while (sampling) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    break;
                }
                AudioTrack at = track;
                if (at == null) {
                    continue;
                }
                try {
                    long head = at.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    int underrun = at.getUnderrunCount();
                    java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
                    fields.put("underrun", underrun);
                    fields.put("headFrames", head);
                    fields.put("headUs", head * 1_000_000L / sampleRate);
                    fields.put("state", stateName(at.getPlayState()));
                    fields.put("sampleRate", sampleRate);
                    rec.event("track", fields);
                } catch (Throwable e) {
                    java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
                    fields.put("error", e.getClass().getName());
                    fields.put("message", e.getMessage() == null ? "" : e.getMessage());
                    rec.event("track_dead", fields);
                    break;
                }
            }
        }, "spike-track-sampler");
        sampler = t;
        t.start();
    }

    public void stopSampling() {
        sampling = false;
        Thread t = sampler;
        if (t != null) {
            t.interrupt();
        }
        sampler = null;
    }

    private static String stateName(int playState) {
        switch (playState) {
            case AudioTrack.PLAYSTATE_PLAYING:
                return "playing";
            case AudioTrack.PLAYSTATE_PAUSED:
                return "paused";
            case AudioTrack.PLAYSTATE_STOPPED:
                return "stopped";
            default:
                return "state:" + playState;
        }
    }
}
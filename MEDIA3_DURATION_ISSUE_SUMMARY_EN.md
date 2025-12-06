# Media3 Audio Duration Issue - Investigation and Fix Summary

## Issue Report

### Problem Description
When playing audio files longer than 20 minutes using Media3 (ExoPlayer) in the HesiMusic application:
- Unable to get correct audio duration
- Cannot drag the progress bar for seeking
- Audio playback works normally

### Technical Environment
- **Media3 Version**: 1.3.1
- **Player**: androidx.media3.exoplayer.ExoPlayer
- **Target API**: Android 36 (minSdk 31)

## Root Cause Analysis

### Media3 1.3.0+ MP3 Parsing Changes

A critical change was introduced in Media3 1.3.0 and later versions in the MP3 extractor (Mp3Extractor):

**Key Change**: When an MP3 file contains an Info frame (instead of a Xing frame), the player assumes the file is Constant Bitrate (CBR) encoded.

**The Problem**:
- Many MP3 files are actually Variable Bitrate (VBR) encoded but still contain Info frames
- Or they are "almost CBR" files with minor frame size variations
- The player estimates the entire file duration based on the first frame size after the Info frame
- For VBR or files with irregular frame sizes, this leads to severely incorrect duration calculations (e.g., a 7-minute file showing as 1+ hour)

### Why It Affects Files Over 20 Minutes

Long audio files are more likely to:
- Use VBR encoding to optimize file size
- Contain incomplete or inaccurate metadata headers
- Lack standard headers like Xing/VBRI that contain seek tables

### Why Playback Works But Seeking Doesn't

- **Playback**: Sequential decoding of audio frames doesn't require accurate duration information
- **Seeking**: Requires precise byte-position to timestamp mapping
- When duration estimation is incorrect, the UI progress bar range doesn't match actual audio content, causing seek operations to fail or position incorrectly

## Official Confirmation

### GitHub Issue Tracking

1. **Issue #1376**: "ExoPlayer wrongly decode some MP3 file"
   - Confirmed to be introduced by commit `4061d476a14314867da2f74ba7049c85568b56eb`
   - Affects VBR MP3 files with Info frames

2. **Issue #2848**: "Some MP3 no more have duration and can't be seeked"
   - Media3 1.5.0+ added improved MP3 handling logic

## Implemented Solution

### Solution: Configure DefaultExtractorsFactory with Constant Bitrate Seeking

Modified `MediaModule.kt` to configure ExoPlayer with constant bitrate seeking:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides
    @Singleton
    fun provideAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

    @Provides
    @Singleton
    fun provideDefaultExtractorsFactory(): DefaultExtractorsFactory =
        DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

    @Provides
    @Singleton
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        audioAttributes: AudioAttributes,
        extractorsFactory: DefaultExtractorsFactory
    ): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context, extractorsFactory)
            )
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
}
```

### Benefits
- Provides instant duration and seekability for MP3 files lacking metadata
- No need to wait for full file loading to perform seek operations
- Fully accurate for CBR files, acceptable minor deviation for VBR files

### Trade-offs
- For VBR files, seek precision may be slightly reduced (usually within a few seconds)

## Future Recommendations

### Short-term
Apply the current fix using `setConstantBitrateSeekingEnabled(true)`

### Long-term
1. **Upgrade to Media3 1.6.0+**: Better MP3 handling logic
2. **Audio File Quality Improvements**: If controlling audio sources
   - Use VBR MP3s with complete Xing headers
   - Use CBR encoded MP3s
   - Consider AAC/M4A format for more reliable metadata
3. **Enhanced Error Handling**: Add logging and user feedback for duration detection issues

## Conclusion

### Issue Attribution
**This is a known issue in the Media3 library**, not an application code problem.

- Introduced by MP3 parsing logic changes in Media3 1.3.0+
- Confirmed and tracked in official GitHub issues
- Media3 1.6.0+ versions include improved solutions

### Implemented Fix
The fix configures Media3's `DefaultExtractorsFactory` to enable constant bitrate seeking, which resolves the issue for most common use cases while maintaining backward compatibility.

## References

### GitHub Issues
- [Issue #1376: ExoPlayer wrongly decode some MP3 file](https://github.com/androidx/media/issues/1376)
- [Issue #1480: ExoPlayer still wrongly decode some MP3 file](https://github.com/androidx/media/issues/1480)
- [Issue #2848: Some MP3 no more have duration and can't be seeked](https://github.com/androidx/media/issues/2848)

### Official Documentation
- [Android Media3 Troubleshooting](https://developer.android.com/media/media3/exoplayer/troubleshooting)
- [Android Media3 Customization](https://developer.android.com/media/media3/exoplayer/customization)
- [Media3 Progressive Playback](https://developer.android.com/media/media3/exoplayer/progressive)
- [DefaultExtractorsFactory API Reference](https://developer.android.com/reference/androidx/media3/extractor/DefaultExtractorsFactory)

### Release Notes
- [Media3 1.5.0 Release](https://android-developers.googleblog.com/2025/01/media3-150-whats-new.html)
- [Media3 1.6.0 Release](https://android-developers.googleblog.com/2025/03/media3-1-6-0-is-now-available.html)
- [Media3 GitHub Releases](https://github.com/androidx/media/releases)

---

**Report Date**: 2025-12-06  
**Project**: HesiMusic  
**Version**: 0.1

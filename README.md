HesiMusic

This repository contains two main parts:

- `ui`: A Vue 3 + Vite + Tailwind web UI that runs inside the Android WebView. Run the dev server with:

  ```bash
  cd ui
  npm install
  npm run dev
  ```

  The UI uses a development mock `src/services/musicBridge.ts` during development. In Android the WebView exposes a `HesiMusicBridge` object; the mock should be replaced by a small adapter that calls the native bridge.

- `app`: Android (Kotlin) app scaffold. Import `e:/code/hesimusic/app` into Android Studio. The WebView in `MainActivity` currently loads `http://10.0.2.2:5173` which points to the host machine dev server.

Next steps and notes:
- Implement scanning and metadata extraction in the Android app. Save metadata using Room (a scaffold is present at `app/src/main/java/com/hesimusic/data`).
- Implement ExoPlayer playback in `player/MusicPlayer.kt` and hook it to `MusicService`.
- Implement a robust JS bridge with typed RPC (use requestId/response promise pattern as described in `design.md`).

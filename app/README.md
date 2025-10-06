HesiMusic Android app (scaffold)

This folder contains a lightweight Android app scaffold you can import into Android Studio.

How to import:
1. Open Android Studio -> Open, choose the folder `e:/code/hesimusic/app`.
2. Let Gradle sync.
3. Run the app on an emulator or device.

Notes:
- The WebView in `MainActivity` currently loads `http://10.0.2.2:5173` which points to the Vite dev server running on the host. Change it to load a file in assets for production.
- The project includes placeholders for `MusicService`, `MusicPlayer`, and Room entities/DAOs. Implement scanning and ExoPlayer wiring inside `MusicService` and `MusicPlayer`.
- JS bridge is TODO: implement a `addJavascriptInterface` to expose a bridge object to the web UI.

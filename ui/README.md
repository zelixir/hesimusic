HesiMusic UI

This is a minimal Vue 3 + Vite + Tailwind development scaffold for the HesiMusic project.

Getting started:

1. cd ui
2. npm install
3. npm run dev

Notes:
- The `musicBridge` service is a mock for development. When running inside Android WebView, replace it with a JS bridge that calls native APIs.

Bundle for Android

To package the built frontend into the Android app assets (so the APK includes a prebuilt UI), run:

```bash
cd ui
npm install
npm run bundle
```

This will run `vite build` and then create `app/app/src/main/assets/frontend.zip`. The Android `MainActivity` will extract this zip on first run and serve it to the WebView under the virtual domain `https://app.frontend/`.
 
Automatic bundle updates

If you replace the `frontend.zip` inside `app/app/src/main/assets/` in a new app build, the app now detects changes to the bundled zip and will atomically unpack the new bundle on first run / when the app resumes. The update process verifies the bundle (looks for `index.html`), swaps it into place atomically, and reloads the WebView so the frontend picks up the new files. The implementation is in the Android app at `com.hesimusic.StaticBundleManager` and `MainActivity`.

For development: after running `npm run bundle` and rebuilding the Android app (or updating the assets in an installed debug APK), simply open the app; it will update the local frontend files automatically.

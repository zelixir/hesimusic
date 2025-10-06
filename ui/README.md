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

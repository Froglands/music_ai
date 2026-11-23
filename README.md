# MusicAI — Android AI Music Studio

Native Android app (Kotlin + Jetpack Compose) with a Node.js backend for ElevenLabs Music generation.

## Project layout
- `android/`: Android application.
- `backend/`: Express API. The ElevenLabs key stays on the server.
- `.github/workflows/android-apk.yml`: GitHub Actions debug APK build.
- `render.yaml`: Render service configuration for the backend.

## App features
- Create a song from a title, lyrics, genre, vocal direction, BPM direction, and mood.
- Edit the title and lyrics after generation, then regenerate the track.
- Preview generated MP3 audio and save it to `Music/MusicAI`.
- Settings page shows the backend configuration and provides a health check.

## Important: configure the backend before generating songs
The Android app currently contains a placeholder backend URL in:

`android/app/src/main/java/com/music_ai/app/MainActivity.kt`

Replace `https://YOUR-BACKEND.example` with the deployed HTTPS backend URL (without a trailing slash), then commit the change.

Deploy the `backend/` directory as a Node.js web service. On Render, use:
- Root directory: `backend`
- Build command: `npm install`
- Start command: `npm start`
- Health check path: `/health`

Set `ELEVENLABS_API_KEY` as a secret environment variable in the hosting provider. Do not put the key in Android code, Git, screenshots, or this README. `ALLOWED_ORIGIN=*` is suitable only for initial testing; restrict it before public release.

Open `https://YOUR-BACKEND/health`. A healthy service returns JSON with `ok: true`.

## Build the APK with GitHub Actions
1. Open the repository's **Actions** tab.
2. Select **Build MusicAI Android APK**.
3. Run the workflow on the branch you want to build, or push/merge a change.
4. Open the completed run and download the `MusicAI-debug-apk` artifact.
5. Extract the ZIP; install `app-debug.apk` on the Android device.

The workflow builds a debug APK. It is suitable for testing, not a signed Play Store release.

## Local build
Open the `android/` folder in Android Studio and run the `app` configuration, or run:

```bash
cd android
gradle assembleDebug
```

## API notes
- The backend accepts `POST /api/generate` and returns MP3 bytes.
- Music generation uses ElevenLabs credits and requires account/model access.
- BPM and vocal gender are sent as creative directions; the generated model is not guaranteed to follow them exactly.
- The backend currently does not store a song library or provide user accounts.

## Before public launch
Add authentication, per-user quotas, abuse protection, restricted CORS origins, request/job tracking, and a signed release build.

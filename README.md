# NadaAI — Android AI Music Studio

Starter project: native Android (Kotlin + Jetpack Compose) + Node.js backend + GitHub Actions cloud APK build.

## Architecture
- `android/`: Android app. Sends song settings and lyrics to the backend, receives MP3 bytes, plays them and saves to Downloads.
- `backend/`: Express server. Keeps `ELEVENLABS_API_KEY` on the server and calls ElevenLabs Music API.
- `.github/workflows/android-apk.yml`: builds a debug APK in GitHub Actions.

## Important limitations
- Tempo/BPM is a musical direction prompt, not a guaranteed exact BPM control in the current Music API.
- Male/female vocal selection is expressed as a composition style instruction; the model may not always obey perfectly.
- This starter uses `music_v2_5` composition plans. Confirm model availability and account access in your ElevenLabs account.
- Generation consumes ElevenLabs credits. Do not expose the API key in the Android app.
- First version has no user accounts or persistent cloud song library. Audio is downloaded to the device; backend does not store it.

## 1. Set up backend (Render, Railway, Fly.io, or any Node host)
1. Create a new GitHub repository and upload this project.
2. Deploy the `backend/` folder as a Node.js web service (Render example: Root Directory `backend`, Build Command `npm install`, Start Command `npm start`).
3. Set environment variables on the hosting provider:
   - `ELEVENLABS_API_KEY` = your ElevenLabs API key
   - `PORT` = usually supplied automatically by the host
   - `ALLOWED_ORIGIN` = `*` for initial testing (tighten later)
4. Deploy and test: open `https://YOUR-BACKEND/health`. It should return `{"ok":true,"service":"nadaai-backend"}`.

Do not paste your API key into source code, GitHub commits, screenshots, or this chat.

## 2. Configure Android backend URL
Open `android/app/src/main/java/com/nadaai/app/MainActivity.kt` and replace:
`https://YOUR-BACKEND.example`
with the deployed backend URL. Do not include a trailing slash.

## 3. Build APK in the cloud
1. Push the repository to GitHub.
2. Open the repository → Actions.
3. Run **Build NadaAI Android APK** (or push to `main`).
4. Download the `NadaAI-debug-apk` artifact from the completed workflow.

## 4. Local build (optional)
Open `android/` in Android Studio and build the `app` module.

## Current API flow
Android POSTs JSON to `/api/generate`. Backend validates inputs, turns the lyric sections into Music v2.5 composition-plan chunks, and calls:
`POST https://api.elevenlabs.io/v1/music?output_format=mp3_48000_192`

The backend returns MP3 bytes with `Content-Type: audio/mpeg`.

## Next milestones
- Add secure per-user authentication and quotas before public release.
- Add server-side generation job tracking and cloud history if desired.
- Add a proper download/share screen and generation cancellation.
- Add account/credit display only through a supported authenticated endpoint; never guess remaining credits.

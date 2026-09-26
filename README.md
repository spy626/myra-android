# MYRA Android

Native Kotlin Phase 1 of MYRA, an Android Gemini Live voice companion.

## Current features

- Gemini Live API over WebSocket (no REST voice and no WebRTC)
- 16 kHz PCM microphone streaming and 24 kHz PCM playback
- Input/output transcripts, text turns, interruption, reconnection and 9-minute renewal
- Animated red/purple orb with audio amplitude response
- Local settings for API key, user name, model, voice and personality
- GitHub Actions debug APK build

## Install a test APK

Open the latest successful **Build Android APK** workflow run, download `myra-debug-apk`, unzip it, and install `app-debug.apk` on an Android 8+ phone.

The Gemini key is entered on-device and is never committed to GitHub. This client-side design is acceptable for private testing; a production release should use short-lived ephemeral tokens issued by a backend.

## Scope

Calls, SMS, contacts, accessibility, overlay and incoming-call features are intentionally deferred until the Live voice foundation is verified on real hardware.

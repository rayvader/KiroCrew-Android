# Kiro Crew for Android

The full Kiro Crew web dashboard in an Android app, with native extras and lots of ways to make it yours.

## Install

1. Open the latest release on your phone: [Releases](https://github.com/rayvader/KiroCrew-Android/releases/latest).
2. Download `KiroCrew-<version>.apk` and open it. Allow "install unknown apps" for your browser when asked.
3. New builds install over old ones and keep your settings (every release is signed with the same key).

## Connect

- **Tailscale (recommended):** turn on Tailscale on the phone, then add `http://<host>.<tailnet>.ts.net:5476`.
- **QR code:** in the dashboard open **Connect your phone**, then tap **Scan QR code** in the app. A sign-in link is opened once so the login cookie is set.
- **Typing tip:** `host:5476` becomes `http://host:5476`; a bare `kiro.example.com` becomes `https://kiro.example.com`.

You can save several servers and switch between them from the top bar.

## What works like the browser

Chat with live streaming (WebSocket), file upload from the phone, downloads (normal, `blob:` and `data:`),
voice input (needs HTTPS — browsers only allow the microphone on secure pages), full-screen video,
links to other sites in your browser, and the login kept between launches.
Folding or rotating the phone does not reload the page.

## Customization (Settings in the app menu)

| Area | Options |
|---|---|
| Server | Several servers, start page (or a custom path), fast page switching |
| Look | Light/dark/system theme, 6 accent colors, force-dark pages, text size 50–200 %, top bar on/off, bottom bar on/off, pick up to 5 bottom-bar pages, full screen |
| Behavior | Pull to refresh, keep screen on, desktop mode, open other sites in browser, double back to exit, custom CSS, user-agent suffix |
| Security | App lock (fingerprint/face/screen lock) with timeout, privacy screen (no screenshots, hidden in recents) |
| Data | Clear cache, sign out (cookies and site data) |

Long-press the app icon for shortcuts to your bottom-bar pages. `kirocrew://open?path=/schedule` links open a page directly.

## Security notes

- Plain `http://` is allowed so Tailscale and LAN hosts work. Tailscale encrypts the link itself; the app shows a warning when plain HTTP goes to a public host.
- User-installed certificate authorities are trusted, so self-hosted HTTPS with a private CA works.
- The page can call one small bridge (`window.KiroCrewAndroid`) to save downloads. It only answers pages from your active server.

## Build

CI ([workflow](.github/workflows/android.yml)) runs the unit tests, builds the release APK and publishes a GitHub release on every push.

Local build needs JDK 17 and the Android SDK:

```bash
./gradlew testDebugUnitTest assembleRelease
```

Without the signing secrets the release APK is signed with the debug key.

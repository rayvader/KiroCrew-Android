# Kiro Crew for Android

[![Android APK](https://github.com/rayvader/KiroCrew-Android/actions/workflows/android.yml/badge.svg)](https://github.com/rayvader/KiroCrew-Android/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/rayvader/KiroCrew-Android)](https://github.com/rayvader/KiroCrew-Android/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84)

Your Kiro Crew dashboard on your phone. The full web dashboard runs inside the app, with native extras and many ways to make it yours.

> Unofficial community app. It needs your own running Kiro Crew gateway.

## Contents

- [Install](#install)
- [Connect to your dashboard](#connect-to-your-dashboard)
- [What works like the browser](#what-works-like-the-browser)
- [Customization](#customization)
- [Deep links and shortcuts](#deep-links-and-shortcuts)
- [Troubleshooting](#troubleshooting)
- [Security](#security)
- [Build from source](#build-from-source)
- [Contributing](#contributing)
- [License](#license)

## Install

1. On your phone, open the [latest release](https://github.com/rayvader/KiroCrew-Android/releases/latest).
2. Download `KiroCrew-<version>.apk` and open it.
3. When asked, allow "install unknown apps" for your browser.

Needs Android 8.0 or newer. Every release is signed with the same key, so new builds install over old ones and keep your settings.

## Connect to your dashboard

| Way | Steps |
|---|---|
| Tailscale (best) | Turn on Tailscale on the phone. Add `http://<host>.<tailnet>.ts.net:5476`. |
| QR code | In the dashboard open **Connect your phone**. In the app tap **Scan QR code**. |
| Your own domain | Add `kiro.example.com`. It becomes `https://kiro.example.com`. |

Typing shortcuts: `host:5476` becomes `http://host:5476`. A bare domain becomes `https://`.

You can save several servers and switch between them from the top bar.

## What works like the browser

- Chat with live streaming (WebSocket)
- File upload from the phone
- Downloads: normal, `blob:` and `data:`
- Voice input (needs HTTPS, because browsers only allow the microphone on secure pages)
- Full-screen video
- Links to other sites open in your browser
- Login kept between launches
- Folding or turning the phone does not reload the page

## Customization

Open **Settings** from the app menu.

| Area | Options |
|---|---|
| Server | Several servers, start page (or a custom path), fast page switching |
| Look | Light, dark or system theme. 6 accent colors. Force-dark pages. Text size 50–200 %. Top bar and bottom bar on or off. Pick up to 5 bottom-bar pages. Full screen. |
| Behavior | Pull to refresh, keep screen on, desktop mode, open other sites in browser, double back to exit, custom CSS, user-agent suffix |
| Security | App lock (fingerprint, face or screen lock) with timeout. Privacy screen (no screenshots, hidden in recents). |
| Data | Clear cache. Sign out (cookies and site data). |

## Deep links and shortcuts

- Long-press the app icon for shortcuts to your bottom-bar pages.
- `kirocrew://open?path=/schedule` opens a page directly.

## Troubleshooting

| Problem | Fix |
|---|---|
| Page does not load | Check Tailscale is on. Check the address and the port (default `5476`). |
| Warning about plain `http` | You used `http` with a public host. Use `https`, or Tailscale. |
| Microphone does not work | Use an `https` address. Browsers block the mic on plain `http`. |
| QR scan fails | Make a fresh code in **Connect your phone**. Codes are single-use. |
| Update will not install | The old app was signed with a different key. Uninstall it, then install the new one. |
| Page looks wrong after a theme change | Settings → Data → Clear cache. |

## Security

- Plain `http://` is allowed so Tailscale and LAN hosts work. Tailscale encrypts the link itself. The app warns you when plain HTTP goes to a public host.
- User-installed certificate authorities are trusted, so self-hosted HTTPS with a private CA works.
- The page can call one small bridge (`window.KiroCrewAndroid`) to save downloads. It only answers pages from your active server.
- App lock and privacy screen are optional, under Settings → Security.

Found a problem? See [SECURITY.md](SECURITY.md).

## Build from source

You need JDK 17 and the Android SDK.

```bash
./gradlew testDebugUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Without signing secrets it is signed with the debug key.

CI ([workflow](.github/workflows/android.yml)) runs the unit tests, builds the APK, runs a smoke test on an Android 14 emulator, and publishes a GitHub release on every push to `main`.

### Signing your own builds (forks)

Add these repository secrets so CI signs with your key:

| Secret | Meaning |
|---|---|
| `SIGNING_KEYSTORE_B64` | Your PKCS12 keystore, base64 encoded |
| `SIGNING_STORE_PASSWORD` | Its password (also used for the key) |

Keep the keystore safe. If you lose it, users must uninstall before they can update.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)

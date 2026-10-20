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
- [Notifications](#notifications)
- [App updates](#app-updates)
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

The app has these steps too. Tap **How to connect** on the welcome screen, in the Add server box, or in the Servers menu.

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

## Notifications

The app turns the dashboard's alerts into Android notifications. It asks for permission once, after your first dashboard page loads.

| What | Where it comes from |
|---|---|
| Bell notes (approvals, cron results, agent messages) | Your gateway's notification feed. Works with the app closed. |
| Chat finished, or a chat waiting for you | The dashboard page itself, so only while the app is still running in the background. For "chat finished", turn on that toast in the dashboard's notification settings. |
| Server needs you to sign in again | The app, when the saved login has run out |

Nothing is shown while you look at the dashboard. It shows its own banner then. Muted and read notes are skipped, the same as the bell badge. Tap a notification to open its page. With several servers, it opens the right one.

Pick how bell notes arrive in **Settings → Notifications → Background alerts**:

| Option | Delay | Battery |
|---|---|---|
| Only while the app is open | Only for the few seconds before Android pauses the app | None |
| Every 15 minutes (default), 30 minutes or 1 hour | Up to the interval. Android can wait longer when the phone sleeps. | Very low |
| Instant | A few seconds | Higher. Keeps one connection open and shows a small "Instant alerts are on" notice. |

**Sound and vibration** opens Android's settings for the app. Each kind of alert has its own channel there: Dashboard alerts, Chat updates, App updates and sign-in, Instant alerts connection.

How it works: the app reads `GET /api/notifications` on your gateway with the app's own login cookie. Instant mode also listens to the dashboard's live `/api/stream`. When the login runs out, the app renews it through `/api/auth/refresh`, the same way the dashboard page does. Nothing goes through Google or any other server.

## App updates

The app checks this repo's [latest release](https://github.com/rayvader/KiroCrew-Android/releases/latest) when it opens (at most every 6 hours) and once a day in the background.

1. When a newer version exists, you get a prompt or a notification.
2. Tap **Update**. The first time, Android asks you to let Kiro Crew install apps.
3. The APK downloads from GitHub. The app checks its size and SHA-256 against the release.
4. Android shows its install screen. Tap **Update**.

Your settings and login stay. Android refuses an APK signed with a different key, so only builds from this repo's CI install over yours. Builds from branches are pre-releases and are never offered.

Turn it off, or check by hand, in **Settings → About**.

## Customization

Open **Settings** from the app menu.

| Area | Options |
|---|---|
| Server | Several servers, start page (or a custom path), fast page switching |
| Notifications | On or off. Background alerts: only while open, every 15/30/60 minutes, or instant. Sound and vibration per kind of alert. |
| Look | Light, dark or system theme. 6 accent colors. Force-dark pages. Text size 50–200 %, or **Auto text size**: wider screens (an unfolded phone, a tablet, landscape) get larger text, and the slider adjusts it. Top bar and bottom bar on or off. Pick up to 5 bottom-bar pages. Full screen. |
| Behavior | Pull to refresh, keep screen on, desktop mode, open other sites in browser, double back to exit, custom CSS, user-agent suffix |
| Security | App lock (fingerprint, face or screen lock) with timeout. Privacy screen (no screenshots, hidden in recents). |
| Data | Clear cache. Sign out (cookies and site data). |
| About | Version. Check for updates on or off. Check now. |

## Deep links and shortcuts

- Long-press the app icon for shortcuts to your bottom-bar pages.
- `kirocrew://open?path=/schedule` opens a page directly.

## Troubleshooting

| Problem | Fix |
|---|---|
| Page does not load | Check Tailscale is on. Check the address and the port (default `5476`). |
| Warning about plain `http` | You used `http` with a public host. Use `https`, or Tailscale. |
| Microphone does not work | Use an `https` address. Browsers block the mic on plain `http`. |
| QR scan fails | Make a fresh code in **Connect your phone**. |
| Update will not install | The old app was signed with a different key. Uninstall it, then install the new one. |
| No notifications | Settings → Notifications. If it says Android is blocking them, tap **Sound and vibration** and allow them. |
| Alerts come late | Pick **Instant**. On Samsung, also set the app's battery use to **Unrestricted** (Android settings → Apps → Kiro Crew → Battery). |
| "Sign in again for alerts" | Open the app once. If the login page shows, sign in or scan a new QR code. |
| Update says "checksum does not match" | The download was damaged. Try again on a better connection. |
| Page looks wrong after a theme change | Settings → Data → Clear cache. |

## Security

- Plain `http://` is allowed so Tailscale and LAN hosts work. Tailscale encrypts the link itself. The app warns you when plain HTTP goes to a public host.
- User-installed certificate authorities are trusted, so self-hosted HTTPS with a private CA works.
- The page can call one small bridge (`window.KiroCrewAndroid`) to save downloads. It only answers pages from your active server.
- Page notifications use a separate channel (`KiroCrewNotify`). WebView gives it only to the main page of your saved servers, so other sites and sandboxed frames cannot post notifications.
- Background alerts talk only to your own gateway, with the login cookie the app already holds.
- Updates come only from `https://github.com/` over HTTPS. The app checks the SHA-256 that GitHub publishes, and Android checks the signing key. The update check sends GitHub your IP address. Turn it off in Settings → About if you do not want that.
- App lock and privacy screen are optional, under Settings → Security.

### Permissions

| Permission | Why |
|---|---|
| Notifications | Show alerts |
| Run in the foreground, start at boot | Instant alerts only. It stays off unless you pick Instant. |
| Install apps | Install updates. Android asks you each time. |
| Camera, microphone | QR scan and voice input, asked when first used |

Found a problem? See [SECURITY.md](SECURITY.md).

## Build from source

You need JDK 17 and the Android SDK.

```bash
./gradlew testDebugUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Without signing secrets it is signed with the debug key.

CI ([workflow](.github/workflows/android.yml)) runs the unit tests, builds the APK, runs a smoke test on an Android 14 emulator, and publishes a GitHub release on every push to `main`. Pushes to `feat/**` and `fix/**` branches publish pre-releases. The smoke test also checks that a page notification reaches Android.

The `e2e` job tests each feature above through the real app on the emulator, against a fake gateway ([`.github/e2e/`](.github/e2e/)). It records a video of every feature. The run page shows a table of what passed. The `e2e-videos` artifact holds the clips and a `demo.mp4` of all of them.

A fork built by its own CI checks its own releases for updates (`GITHUB_REPOSITORY` is baked in at build time).

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

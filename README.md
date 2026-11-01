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
- [Froyo widget](#froyo-widget)
- [App updates](#app-updates)
- [Customization](#customization)
- [Deep links and shortcuts](#deep-links-and-shortcuts)
- [Troubleshooting](#troubleshooting)
- [Security](#security)
- [Tech stack](#tech-stack)
- [Project structure](#project-structure)
- [Build from source](#build-from-source)
- [Configuration](#configuration)
- [CI and releases](#ci-and-releases)
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

A small dot shows whether the app can reach the server: **green** connected, **red** not connected, grey while it checks. It sits on the Servers icon in the top bar (on the Kiro ghost button when the top bar is off), and on each server in the Servers list. The app asks the server's public `/api/health` every 30 seconds, only while the app is open.

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

## Froyo widget

A home-screen widget, **Froyo · Upcoming**, lists the open items of the crewmate Froyo (`froyo-personal-butler`): bookings, flights, deliveries and deadlines. Add it from **Settings → Add Froyo widget**, or long-press the home screen and pick it under Widgets.

- The header shows how many items are open and stays at the top. Below it, every open item is in a list you scroll with a finger, soonest first: kind icon (📅 booking, ✈ flight, 📦 delivery, ⏰ deadline), due day and time in 24-hour time (the day only when there is no time), and the title.
- Due within 24 hours is orange. Overdue is red and says **late**.
- Tap a row to open Froyo's chat in the app.
- Tap **Done** on a row. The app asks first. Say yes and the row goes, with **Undo** for 5 seconds. Then the app sends `Done: <title>` to Froyo's chat, and Froyo marks it done. Undo after that sends `Undo done: <title>`. With app lock on, Done asks you to unlock first.
- It refreshes about every 30 minutes, when you open the app, and after a Done. Nothing open shows **Nothing coming up**. When the server cannot be reached, the last list stays, with **as of HH:mm**.
- Resize it from 4×1 to 4×4. A taller widget shows more rows at once; the list shows whole rows and scrolls to the rest. Rows are never squeezed or overlapped.

How it works: the app reads Froyo's thread with `POST /api/members/froyo-personal-butler/thread` and its open items from `GET /api/members/froyo-personal-butler/dashboard` (the `open_items` field of Froyo's dashboard), with the app's own login cookie. Done is a chat message sent with `POST /api/chat`, the same call the dashboard's chat box makes.

The widget shows item titles on the home screen, also while app lock is on.

## App updates

The app checks this repo's [latest release](https://github.com/rayvader/KiroCrew-Android/releases/latest) when it opens (at most every 6 hours) and once a day in the background.

1. When a newer version exists, you get a prompt or a notification. The prompt lists what's new in that version (the same list as on the release page).
2. On Wi-Fi (any unmetered network) the APK downloads by itself, through Android's downloader, which never switches to mobile data for it. When it is done you get a **Kiro Crew 2.0.x downloaded** notification, and the prompt reads **Version 2.0.x is ready to install** with an **Install** button.
3. On mobile data nothing downloads until you tap **Update**. The first time, Android asks you to let Kiro Crew install apps.
4. The app checks the APK's size and SHA-256 against the release. A version is downloaded once: a finished APK is reused, and a running download is left to finish.
5. Android shows its install screen. Tap **Update**. Installing always needs your tap; Android requires it.

Your settings and login stay. Android refuses an APK signed with a different key, so only builds from this repo's CI install over yours. Builds from branches are pre-releases and are never offered. Every release page has a **What's new** list: the pull requests merged since the last release (commits, for pre-releases).

Turn it off, or check by hand, in **Settings → About**.

### Kiro Crew updates

The app also asks each saved server whether **Kiro Crew itself** has a new version (the gateway's `GET /api/update/check`). It checks when the app opens (at most every 6 hours), once a day in the background, and when you tap **Check now**. A new version shows one notification per server. Tap it to open that dashboard's **Settings → About**, then tap **Update** there. The app never updates the gateway itself, and a check the gateway could not finish shows nothing. Turn it off in **Settings → Notifications → Check for Kiro Crew updates**.

## Customization

Open **Settings** from the app menu.

| Area | Options |
|---|---|
| Server | Several servers, start page (or a custom path), fast page switching |
| Notifications | On or off. Background alerts: only while open, every 15/30/60 minutes, or instant. Sound and vibration per kind of alert. |
| Look | Light, dark or system theme. 6 accent colors. Force-dark pages. Text size 50–200 %, or **Auto text size**: slightly bigger text on a folded phone (110 %), slightly smaller on an unfolded phone or a tablet (90 %), and the slider adjusts it. Turning the phone does not change it. Top bar and bottom bar on or off. Pick up to 5 bottom-bar pages. Full screen. |
| Behavior | Pull to refresh, keep screen on, desktop mode, open other sites in browser, double back to exit, custom CSS, user-agent suffix |
| Security | App lock (fingerprint, face or screen lock) with timeout. Privacy screen (no screenshots, hidden in recents). |
| Data | Clear cache. Sign out (cookies and site data). |
| About | Version. Past changes (what changed in each version, read from GitHub when you tap it). Check for updates on or off. Check now. |

## Deep links and shortcuts

- Long-press the app icon for shortcuts to your bottom-bar pages.
- Share to Kiro Crew from any app: text, links and files (up to 10 files, 25 MB) go into the chat box. Nothing is sent until you tap send.
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
- Shared files are read only from other apps' `content://` links, never `file://` links or the app's own files. Only your active server's page can read them, once.
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

## Tech stack

- Kotlin 1.9, Android Gradle Plugin 8.5, Gradle 8.7 (wrapper), Java 17
- `minSdk` 26 (Android 8.0), `compileSdk` / `targetSdk` 34
- AndroidX: AppCompat, Activity, Core, WebKit (the dashboard runs in a `WebView`), Preference (settings screen), SwipeRefreshLayout, Biometric (app lock), WorkManager (background alert and update checks)
- Material Components for the UI
- ZXing Android Embedded for QR scanning
- JUnit 4 (plus `org.json`) for plain-JVM unit tests
- CI: GitHub Actions, Android 14 emulator via `reactivecircus/android-emulator-runner`, Python scripts for the smoke test, e2e tests and release notes

Single Gradle module (`:app`). No backend of its own: it talks only to your Kiro Crew gateway and, for app updates, to GitHub releases.

## Project structure

| Path | What it holds |
|---|---|
| `app/src/main/java/io/github/rayvader/kirocrew/` | Activities and Android glue: `MainActivity` (WebView dashboard, bars, share, downloads), `SettingsActivity`, `ProfilesActivity` (servers), `CreditsActivity` (About), alerts (`AlertService`, `AlertSync`, `AlertsClient`, `Notifier`), updates (`Updates`, `UpdateFlow`, `ApkDownloads`, `GatewayUpdates`), Froyo widget (`FroyoWidget`, `FroyoDoneActivity`), `AppLock`, `Prefs`, `Theming`, `ShareInbox` |
| `app/src/main/java/io/github/rayvader/kirocrew/core/` | Pure Kotlin logic with no Android types: URL handling, profiles, injected page scripts, `data:` codecs, alert parsing, share rules, version compare, auto text size. Unit tested. |
| `app/src/main/res/` | Layouts, menus, drawables, strings, themes, `xml/preferences.xml` (settings), `xml/network_security_config.xml` |
| `app/src/test/` | JUnit tests for `core/` |
| `.github/workflows/android.yml` | Unit tests, release APK, emulator smoke and e2e tests, GitHub release |
| `.github/scripts/` | `smoke.sh` (emulator smoke test), `release_notes.py` (release changelog), `find_bounds.py` |
| `.github/e2e/` | Fake gateway (`gateway.py`), UI driver and per-feature tests (`run.sh`, `ui.py`), report and demo video (`report.py`) |
| `.github/smoke-site/` | Static test page for the smoke test |

## Build from source

You need JDK 17 and the Android SDK (platform 34). The Gradle wrapper fetches Gradle itself.

```bash
git clone https://github.com/rayvader/KiroCrew-Android.git
cd KiroCrew-Android
./gradlew testDebugUnitTest            # unit tests
./gradlew assembleDebug                # debug APK
./gradlew testDebugUnitTest assembleRelease   # what CI runs
```

Debug APKs land in `app/build/outputs/apk/debug/`, release APKs in `app/build/outputs/apk/release/`. Without a signing key the release APK is signed with the debug key, so it still installs. To install on a connected phone or emulator: `adb install -r app/build/outputs/apk/release/app-release.apk`.

Note that an APK signed with a different key will not install over the release build from GitHub. Uninstall that one first.

## Configuration

The app itself needs no config files: servers and every setting are set in the app. Build-time behavior comes from these environment variables (names only; CI sets them):

| Variable | Effect |
|---|---|
| `GITHUB_RUN_NUMBER` | Sets `versionCode` and `versionName` (`2.0.<n>`). Defaults to `1` locally. |
| `GITHUB_REPOSITORY` | The `owner/repo` whose releases the app checks for updates. Defaults to `rayvader/KiroCrew-Android`. |
| `SIGNING_STORE_FILE` | Path to a PKCS12 keystore. When set and present, release builds are signed with it. |
| `SIGNING_STORE_PASSWORD` | Keystore and key password |
| `SIGNING_KEY_ALIAS` | Key alias. Defaults to `kirocrew`. |

## CI and releases

CI ([workflow](.github/workflows/android.yml)) runs the unit tests, builds the APK, runs a smoke test on an Android 14 emulator, and publishes a GitHub release on every push to `main`. Pushes to `feat/**` and `fix/**` branches publish pre-releases. The smoke test also checks that a page notification reaches Android.

The `e2e` job tests each feature above through the real app on the emulator, against a fake gateway ([`.github/e2e/`](.github/e2e/)). It films each feature, but keeps a clip only when the feature fails, or when it can only be checked by eye (theme colors, the update toast). The run page shows a table of what passed. The `e2e-videos` artifact holds the kept clips and a `demo.mp4` of them. For a clip of every feature, start the workflow by hand (**Actions → Android APK → Run workflow**) with **keep_all_video** ticked.

Releases are tagged `v2.0.<run number>` and carry `KiroCrew-2.0.<run number>.apk`. The release notes come from [`.github/scripts/release_notes.py`](.github/scripts/release_notes.py). Pull requests only build and keep the APK as a workflow artifact.

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

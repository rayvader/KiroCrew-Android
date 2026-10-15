# Contributing

Thanks for helping. Small, focused changes are easiest to review.

## Set up

You need JDK 17 and the Android SDK.

```bash
git clone https://github.com/rayvader/KiroCrew-Android.git
cd KiroCrew-Android
./gradlew testDebugUnitTest
```

## Layout

| Path | What it holds |
|---|---|
| `app/src/main/java/io/github/rayvader/kirocrew/` | Activities, theming, app lock, downloads |
| `.../core/` | Pure Kotlin logic (URLs, profiles, scripts, codecs). Unit tested. |
| `app/src/main/res/` | Layouts, menus, strings, settings screen |
| `.github/workflows/android.yml` | Build, test, smoke test, release |

## Pull requests

1. Branch from `main` (`feat/...` or `fix/...`).
2. Keep logic in `core/` where you can, and add a unit test.
3. Run `./gradlew testDebugUnitTest assembleRelease` before you push.
4. Open a PR. CI must pass, including the emulator smoke test.

Never commit keystores, passwords or real server addresses.

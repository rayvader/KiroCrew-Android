# Security policy

## Report a problem

Please do not open a public issue for a security bug.
Use GitHub's private reporting: **Security → Report a vulnerability** on this repository.

Include what you did, what happened, and the app version (the release name, for example `v1.0.5`).

## Scope

In scope: the Android app in this repository (the WebView host, the download bridge, app lock, deep links).

Out of scope: the Kiro Crew gateway and dashboard itself. Report those to the Kiro Crew project.

## Notes for users

- Prefer Tailscale or `https://` addresses.
- Plain `http://` to a public host shows a warning. Do not ignore it.
- Release APKs are signed with one fixed key. Only install APKs from this repository's [Releases](https://github.com/rayvader/KiroCrew-Android/releases).

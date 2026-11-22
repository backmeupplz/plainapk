# Plain APK

A plain Android app store that installs and silently updates apps straight from their GitHub, Forgejo and GitLab releases, with no backend, no account and no trackers. Site: https://plainapk.com.

Download: https://github.com/backmeupplz/plainapk/releases/latest/download/plainapk.apk (Android 12+). [SPEC.md](SPEC.md) describes the design.

The app is plain Kotlin with no libraries: `./gradlew testDebugUnitTest assembleRelease` (JDK 21, Android SDK). Releases: `tea release create --repo backmeupplz/plainapk --tag vX.Y.Z` builds, signs and attaches `plainapk.apk` on Forgejo and GitHub; versionCode is the build minute, so nothing needs bumping.

The catalogue (`index.json` and `icons/` on plainapk.com) is built by `tools/catalogue.py` in the GitHub Pages workflow on the mirror (`.github/workflows/pages.yml`), on every push and nightly: every IzzyOnDroid and F-Droid app whose source is on GitHub, GitLab or Codeberg and whose latest release carries an APK, plus `catalogue.txt`. `docs/index.json` is only a fallback.

The site lives in `docs/` and is served by GitHub Pages from the GitHub mirror, behind Cloudflare. The share image is rendered from `src/og.html` with headless Chrome:

```sh
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
  --window-size=1200,630 --screenshot="$PWD/docs/og.png" "file://$PWD/src/og.html"
```

Bump `og.png?v=N` in `docs/index.html` after re-rendering.

Source of truth: https://git.borodutch.com/backmeupplz/plainapk, mirrored to https://github.com/backmeupplz/plainapk.

MIT license.

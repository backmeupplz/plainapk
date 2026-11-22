# Plain APK spec

A small native Android app store that installs and silently updates apps from the APKs attached to GitHub, Forgejo and GitLab releases. There is no backend, no account and no telemetry. The site and catalogue index are static files on GitHub Pages behind Cloudflare at plainapk.com.

## Hard requirements

- No server of our own. The phone talks to the forges directly and keeps all state in local SQLite.
- No account, analytics, crash reporting, Google Play services or third-party SDKs.
- Native Kotlin and as small and fast as possible. minSdk 31 (Android 12), because older versions can't update silently.
- Works the same on GrapheneOS and other de-Googled phones.
- Handles projects that publish a release every hour.
- Updates itself.

## Catalogue

- `catalogue.txt` in this repo, one repository per line (optionally followed by an asset pattern), added by pull request.
- `tools/seed.py` downloads the APK each line resolves to and writes `docs/index.json` with the real package name, label, repo description and signing-cert SHA-256. Pages serves it at `https://plainapk.com/index.json` (Cloudflare in front, encrypted to GitHub). That is the only file the app fetches from us.
- Later: run the seed in CI on catalogue PRs and nightly, and pull candidates from the IzzyOnDroid and F-Droid indexes (metadata only: F-Droid builds are signed by F-Droid).
- Users can also add any repo URL by hand.

## Checking for updates

- **GitHub:** poll `https://github.com/O/R/releases.atom` with `If-None-Match`. That is the website, not the API, so the 60/h unauthenticated API limit doesn't apply. When the tag changes, read the asset links from `https://github.com/O/R/releases/expanded_assets/<tag>`. Use the API, with an optional user token, only as a fallback.
- **Forgejo:** `/O/R/releases.rss` or the API.
- **GitLab:** the API (500 requests a minute unauthenticated).
- WorkManager periodic work, default every hour (minimum 15 minutes), on unmetered networks unless the user says otherwise.

## Choosing the asset

The default needs no configuration. Take the latest non-draft, non-prerelease release with at least one `.apk`. Prefer the file whose name contains the device's primary ABI, otherwise one with no ABI in its name, then a file named after the repository, then the shortest name; names containing `debug`, `fdroid`, `unsigned`, `wear` or `tv` go last. A catalogue line can override this with an asset pattern, and each app can opt into prereleases in its sheet.

## Installing and verifying

- Before installing, check that the package name matches, `versionCode` is greater than the installed one, and the signing cert matches the pinned `signer` or the installed app's cert. On any mismatch, discard the file and tell the user.
- Use `PackageInstaller` sessions with `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` and declare `UPDATE_PACKAGES_WITHOUT_USER_ACTION`. Updates are silent when Plain APK is the app's installer (or update owner) or is updating itself, and the app targets a recent SDK (API 35+ on Android 17). Otherwise handle `STATUS_PENDING_USER_ACTION` with a notification.
- On Android 14+ use install constraints so an app isn't replaced while it's in the foreground.

## Installed apps

Using `QUERY_ALL_PACKAGES`, match installed apps against the catalogue by package name and signing cert, and sort them into three groups:
- **Silent:** Plain APK is already the installer.
- **One confirmation, then silent:** installed by another store but signed with the same key.
- **Signer mismatch:** typical for F-Droid builds. Updating needs an uninstall, which loses the app's data, so warn and never download.

## To verify on a device

- Whether Play keeps update ownership on Android 14+, which would mean a prompt on every update.
- Whether Android throttles silent updates per app, and by how much.
- An hourly-release test app updating silently on GrapheneOS.

## Risk

From 2026-09-30, Google's developer verification blocks apps from unverified developers on certified devices in Brazil, Indonesia, Singapore and Thailand, going global in 2027. GrapheneOS isn't a certified device, so this shouldn't apply there.

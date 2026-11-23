# Publishing an Android app for Plain APK

Plain APK (https://plainapk.com) installs Android apps straight from the releases of their GitHub, Forgejo (Codeberg or self-hosted) or GitLab repository and updates them in the background once a day, always to the newest release, so even hourly builds are fine. There is no store account, no review queue and no server: if your releases carry an APK, people can already install your app by pasting your repository link into Plain APK.

This page is written so that a person or an AI coding agent can follow it as-is. It is also served at https://plainapk.com/llms.txt.

## The four rules

1. **Attach a signed `.apk` to every release.** Plain APK reads the newest full release (drafts and pre-releases are skipped unless the user switches pre-releases on for your app). Source archives, `.aab`, `.apks` and checksum files are ignored.
2. **Sign every build with the same key.** Plain APK pins the signing certificate on first install and refuses any APK signed differently, and Android refuses it too. Keep the keystore out of the repository (a CI secret) and back it up: a lost key means your users can never update again.
3. **Make `versionCode` strictly increase.** Plain APK only installs a build whose `versionCode` is higher than the installed one. The easiest rule for frequent or automated releases is the build time in minutes: `versionCode = epoch seconds / 60` (fits in an int until the year 6053).
4. **Target a recent SDK.** Android only lets Plain APK update an app silently when it targets a recent API level (API 35+ on Android 17, one level lower per older Android version). Older targets still update, but the user has to tap to confirm each time.

Tag names and release titles can be anything (`v1.2.3`, `build-29923105`, `nightly-2026-11-22T14`). Plain APK compares the tag only to notice that something new exists; the decision to install comes from `versionCode`.

## Several APKs in one release

Plain APK picks, in order:

1. the APK built for the phone's processor (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, recognised in the file name, for example `app-arm64-v8a-release.apk`), otherwise a universal APK (no ABI in the name), otherwise one for another ABI the phone runs;
2. among those, a file whose name contains the repository name;
3. then the shortest name.

Names containing `debug`, `fdroid`, `unsigned`, `wear` or `tv` are only used when nothing else fits. If you ship flavors that this would get wrong, list your app in the catalogue with a file pattern (below).

## Listing the app in the catalogue

The catalogue that every Plain APK user browses is `catalogue.txt` in https://git.borodutch.com/backmeupplz/plainapk (mirrored at https://github.com/backmeupplz/plainapk). Open a pull request that adds one line:

```
https://github.com/you/yourapp
https://github.com/you/yourapp  yourapp-*-foss.apk
```

The optional second column is a file name pattern (`*` matches anything) for releases with several APKs. A nightly job reads your APK's package name and signing certificate straight from the release file, so phones can tell whether an installed copy can be updated. Apps listed on IzzyOnDroid or F-Droid with their source on GitHub, GitLab or Codeberg are picked up automatically.

## A release workflow to copy

This builds a signed APK on every push to `main` and publishes it as a release. It works on GitHub Actions as-is and on Forgejo Actions with the change noted at the end. Add to `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "dev"
    }
}
```

Create a keystore once (`keytool -genkeypair -keystore release.jks -alias release -keyalg RSA -keysize 4096 -validity 36500`), then add the repository secrets `KEYSTORE_BASE64` (`base64 -i release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Save `.github/workflows/release.yml`:

```yaml
name: Release APK
on:
  push:
    branches: [main]
permissions:
  contents: write
jobs:
  apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Build and sign
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: |
          export VERSION_CODE=$(( $(date +%s) / 60 ))
          export VERSION_NAME="build-$VERSION_CODE"
          echo "VERSION_NAME=$VERSION_NAME" >> "$GITHUB_ENV"
          ./gradlew assembleRelease
          printf '%s' "$KEYSTORE_BASE64" | base64 -d > release.jks
          apksigner=$(ls -d "$ANDROID_HOME"/build-tools/* | tail -1)/apksigner
          "$apksigner" sign --ks release.jks --ks-pass env:KEYSTORE_PASSWORD --ks-key-alias "$KEY_ALIAS" \
            --key-pass env:KEY_PASSWORD --out yourapp.apk app/build/outputs/apk/release/app-release-unsigned.apk
          rm release.jks
      - name: Publish the release
        env:
          GH_TOKEN: ${{ github.token }}
        run: gh release create "$VERSION_NAME" yourapp.apk --target "$GITHUB_SHA" --title "$VERSION_NAME" --notes "Automated build"
```

If your Gradle build already signs release builds, drop the `apksigner` lines and attach `app/build/outputs/apk/release/app-release.apk`.

**Forgejo or Codeberg:** put the same file in `.forgejo/workflows/` and replace the last step with the Forgejo API (the job token can create releases):

```yaml
      - name: Publish the release
        env:
          TOKEN: ${{ github.token }}
        run: |
          api="$GITHUB_SERVER_URL/api/v1/repos/$GITHUB_REPOSITORY/releases"
          id=$(curl -fsS -H "Authorization: token $TOKEN" -H 'Content-Type: application/json' \
            -d "{\"tag_name\":\"$VERSION_NAME\",\"target_commitish\":\"$GITHUB_SHA\",\"name\":\"$VERSION_NAME\"}" "$api" | jq .id)
          curl -fsS -H "Authorization: token $TOKEN" -F "attachment=@yourapp.apk" "$api/$id/assets?name=yourapp.apk"
```

**GitLab:** create the release with `release-cli` (or the Releases API) and add the APK as a release asset link; Plain APK reads `assets.links` of the latest release.

## Checking the result

- `https://github.com/you/yourapp/releases/latest` should show the new release with the `.apk` attached.
- `apksigner verify --print-certs yourapp.apk` should print the same certificate SHA-256 for every build.
- Paste the repository link into Plain APK's search field and tap Add: it installs the APK, and later releases arrive on their own within a day (or right away with the refresh button).

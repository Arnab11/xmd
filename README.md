# Xmd — Xtreme Media Downloader

An Android download manager built for FuckingFast share links (`fuckingfast.co`), with fitgirl-repacks page support and Cloudflare/Turnstile challenge handling — an Android port of the original PyQt5 desktop downloader.

## Features

- Paste `fuckingfast.co` share links, `dl.fuckingfast.co` direct links, or a `fitgirl-repacks.site` page URL — the app expands source pages into their share links automatically.
- If a link needs Cloudflare/Turnstile verification, an in-app WebView opens the share page so you can clear the challenge yourself; once cleared, the direct URL is captured automatically.
- Resumable, pause/cancel-able downloads that run in a foreground service, so they survive backgrounding the app.
- IDM-style **auto-categorized downloads** — files are sorted by extension into `Videos`, `Music`, `Documents`, `Apps`, or `Others` subfolders, no manual picking required.
- Download queue persists across app restarts (Room-backed), so nothing is lost if the app process is killed.

## Project structure

```text
app/src/main/java/com/utsav/ffdownloader/
├─ core/
│  ├─ LinkParser.kt        # share/direct/fitgirl link parsing & validation
│  ├─ DownloadEngine.kt    # resumable streaming download engine
│  ├─ CategoryDetector.kt  # extension -> DownloadCategory mapping
│  ├─ QueueRepository.kt   # in-memory + Room-backed queue state
│  ├─ Settings.kt          # persisted app settings
│  └─ db/                  # Room entities/DAO for the queue
├─ service/
│  └─ DownloadService.kt   # foreground service driving downloads per category folder
├─ ui/
│  ├─ MainActivity.kt, HomeFragment.kt, DownloadsFragment.kt, QueueAdapter.kt
│  └─ ChallengeActivity.kt # WebView for clearing Cloudflare/Turnstile challenges
└─ FfApp.kt                # Application class
```

## Building

Requires JDK 17 and the Android SDK (compileSdk 34, minSdk 26).

```bash
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # unsigned release APK, then sign with apksigner (see below)
```

Or open the project in Android Studio and run/build normally.

### Signing a release build

Release builds are intentionally unsigned by Gradle — `assembleRelease` produces `app-release-unsigned.apk`, which you sign explicitly with `apksigner`:

```bash
apksigner sign --ks your-release.jks --ks-key-alias <alias> \
  --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

CI does this automatically on push to `main` via `.github/workflows/android-build.yml`, using repo secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); the signed APK is uploaded as a build artifact.

## Releases

Tagged pushes trigger `.github/workflows/release.yml`, which builds a signed release APK and publishes it to GitHub Releases with a SHA-256 checksum and notes pulled from `CHANGELOG.md`.

To cut a release:

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts` and add a new `## [x.y.z]` section to the top of `CHANGELOG.md`, then commit and push those to `main`.
2. Tag the commit with `vX.Y.Z` (must match `app/build.gradle.kts`'s `versionName`) and push the tag:

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

3. The `release-for-github` job runs automatically and publishes the GitHub Release with `Xmd-v1.0.0.apk` attached.

You can also trigger it manually from the **Actions** tab → **Make release** → **Run workflow**, entering the tag name (e.g. `v1.0.0`) without needing to push a tag first.

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE` — fetching links and downloading
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` — background download progress notification
- `MANAGE_EXTERNAL_STORAGE` — saving downloaded files into category subfolders

## License

Licensed under the GNU Affero General Public License v3.0 — see [LICENSE](LICENSE).

Only download content you are authorized to access.

# Update manifest and release workflow

## v1.0.1 release status

The source is prepared for version **1.0.1 (versionCode 2)**. The production APK has been built locally and verified. The app’s default update manifest endpoint is:

`https://raw.githubusercontent.com/itsmebillah/app-internet-control/main/update-manifest.json`

The manifest is prepared locally as `update-manifest.json` and is intentionally excluded from the source push. It must be added to `main` only after the matching signed APK is published on GitHub Releases. Until then, the raw URL is unavailable and the update feed is not live. The official human-readable destination is `https://github.com/itsmebillah/app-internet-control/releases/latest`.

The checked-in [`update-manifest.example.json`](update-manifest.example.json) demonstrates the exact fields accepted by `UpdateManifestParser`; it is not the active feed. The schema is:

```json
{
  "latestVersionName": "1.0.1",
  "latestVersionCode": 2,
  "releaseNotes": "Initial public release. See the release page for details.",
  "minimumSupportedVersionCode": 1,
  "releaseUrl": "https://github.com/itsmebillah/app-internet-control/releases/latest"
}
```

The parser requires a non-empty version name, positive numeric version codes with minimum no greater than latest, non-empty release notes (up to 4,000 characters), and an HTTPS release URL. The HTTPS fetcher does not follow redirects. `latestVersionCode` is compared numerically; the app does not install downloaded APKs. Optional updates can be deferred for seven days, and automatic checks are rate-limited to once per day. A minimum-version notice does not block startup.

## Build configuration

`app/build.gradle.kts` defaults to the public raw GitHub URL above. A Gradle property can override it for a separate build:

```powershell
.\gradlew.bat assembleDebug -PappUpdateManifestUrl="https://updates.example.org/app-internet-control.json"
```

Do not put credentials in this property. A production update manifest and release page must both be public over HTTPS.

## Signed release workflow

1. Increase `versionCode` for every release and set the corresponding `versionName` in `app/build.gradle.kts`. Never reuse a published code or replace the existing production signing key.
2. Keep signing material outside Git. The release build reads `APP_RELEASE_STORE_FILE`, `APP_RELEASE_STORE_PASSWORD`, `APP_RELEASE_KEY_ALIAS`, and `APP_RELEASE_KEY_PASSWORD` from the build process environment. It fails rather than producing a debug-signed release when these are unavailable.
3. Run the full unit tests and build the release artifact:

   ```powershell
   .\gradlew.bat testDebugUnitTest --no-daemon --console=plain
   .\gradlew.bat assembleRelease --no-daemon --console=plain
   ```

4. Verify the APK signature and certificate fingerprint with Android SDK `apksigner`; verify alignment with `zipalign`; inspect package, version name, and version code. Calculate and publish the APK SHA-256 checksum in the release notes or a checksum file.
5. Create the GitHub Release with tag matching the APK version and attach only the verified production-signed APK. Do not attach keystores, backups, recovery notes, local signing configuration, or debug APKs.
6. Only after the release and APK asset are available, add `update-manifest.json` using the schema above. Set its `releaseUrl` to the official latest-release page. Publish the manifest to `main` and verify that its raw HTTPS URL returns valid JSON without redirecting, and that the release page and APK asset exist.
7. Verify the update policy using installed version code 1 against manifest code 2; this is a version-policy check, not proof of a live update prompt. Test the prompt on a device after the release is published.

GitHub Releases are intended for direct APK distribution. Users install through Android’s package installer and may need to allow their browser or file manager to install unknown apps. App Internet Control never silently installs APKs. Google Play users should receive updates through Google Play; Play In-App Updates are not implemented.

## Current release verification

- The four existing user-scope signing variables were loaded into the release PowerShell process without printing their values. The existing keystore alias and certificate fingerprint were verified; the production key was not changed.
- `:app:signingReport` selected the release configuration. `clean assembleRelease` succeeded for v1.0.1/versionCode 2. `apksigner`, certificate fingerprint, and `zipalign` checks passed.
- A forced release rebuild changed the artifact hash from the earlier local output `4D656A3C4CE64183F562DDC8EE1DDBEA3DDE6C277E8469A8819889F3C068A389`. The rebuilt artifact was separately re-verified; do not treat the earlier hash as its checksum.
- Current verified APK: `app/build/outputs/apk/release/app-release.apk`; 2,437,606 bytes; SHA-256 `A531CF464E9A5BADC19517C43AAA29E5E64CB8DBAC1E374E416EDBA998C9E153`.
- The reviewed source changes are pushed to `main` at commit `9ec0da791d97e918feed0f5f82311df8bb1e637d`. The release itself remains unpublished.
- GitHub CLI is not installed, and the available GitHub integration does not expose release creation or binary asset upload. No GitHub Release, tag, or public APK download has been created or verified.
- The active manifest file is not in Git and has not been published. After the APK is attached to the release, add the prepared `update-manifest.json` to `main`, publish it, then verify the raw HTTPS URL and release page.

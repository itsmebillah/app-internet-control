# App Internet Control

App Internet Control is a native Android prototype for managing network access for apps on a device. Feature 1 provides a launchable-app list, persistent per-app selections, and a consent-gated experimental VPN packet-discard implementation. Traffic blocking has not been verified on a physical device; the UI labels enforcement as unverified. No schedules, analytics, accounts, or cloud sync are implemented.

## Approved Android levels

- Minimum SDK: API 29 (provisional pending VPN and scheduling validation on physical devices)
- Compile SDK: API 37
- Target SDK: API 37
- Initial physical-device test matrix: API 29, API 34, and API 37/current Android; test Wi-Fi and mobile data where available and multiple OEMs when practical.

Routing selected apps through `VpnService` and dropping their traffic is implemented as a prototype, but remains an unvalidated hypothesis until real-device tests pass.

## Installed-app list and prototype blocking

The list uses Android's launcher-intent package-visibility query, so it shows launchable apps, not necessarily every installed package. The app does not request `QUERY_ALL_PACKAGES`. Per-app selections are stored locally on the device. Enabling them asks Android for VPN consent. On Android 13 and newer the app asks for notification permission to show the ongoing VPN status in the notification drawer, but continues if that optional permission is declined. The prototype routes only selected visible packages through a local VPN interface and discards packets read there. Until tested on physical devices, this must not be treated as verified traffic blocking.

## Schedules

Schedules support one-time dates and weekly weekday rules with local start/end times and selected launchable apps. They survive app restarts and are re-evaluated after boot, clock/time-zone changes, app replacement, and scheduled boundaries. An active schedule overrides the manual selection for its apps; when active rules conflict, blocking wins. The scheduler uses inexact Android alarms, which can be delayed, and Android may prevent a background VPN service start. The screen reports pending/inactive status instead of claiming enforcement. Exact-alarm access is not requested.

## Prerequisites

- Android Studio with a compatible JDK. This foundation uses Android Studio's bundled JBR 25; Gradle 9.6 supports running on JDK 25 and AGP 9.4 requires JDK 17 or newer.
- Android SDK Platform 37 and SDK Build Tools 36.0.0.
- Android SDK Platform Tools for ADB and a connected device for on-device testing.

Build versions are pinned to Android Gradle Plugin 9.4.0, Gradle Wrapper 9.6.0, and AGP built-in Kotlin 2.2.10. AGP 9.4 supports API 37 and specifies Gradle 9.6.0 and Build Tools 36.0.0 as its defaults.

The repository does not track `local.properties`, SDK paths, signing files, credentials, or other machine-specific configuration. Android Studio can use its configured SDK path; for command-line builds set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your installed SDK directory if Gradle cannot locate it.

## Build and verify

On Windows, from the repository root:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

The Gradle Wrapper downloads the pinned Gradle distribution on first use and verifies its SHA-256. The debug build output is under `app\build\outputs\apk\debug`; no release APK or signing setup is included.

## Project documents

- [Master plan](MASTER_PLAN.md) — requirements, approved platform levels, risks, and acceptance criteria.
- [Development plan](DEVELOPMENT_PLAN.md) — staged implementation roadmap.

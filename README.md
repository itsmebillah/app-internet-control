# App Internet Control

App Internet Control is a planned native Android application for managing internet access for apps installed on a device. This repository currently contains only the minimal Kotlin project foundation and a basic launch screen; no product controls are implemented.

## Approved Android levels

- Minimum SDK: API 29 (provisional pending VPN and scheduling validation on physical devices)
- Compile SDK: API 37
- Target SDK: API 37
- Initial physical-device test matrix: API 29, API 34, and API 37/current Android; test Wi-Fi and mobile data where available and multiple OEMs when practical.

The idea of routing selected blocked apps through `VpnService` and dropping their traffic remains an unvalidated hypothesis, not an implemented or proven architecture.

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

# App Internet Control

App Internet Control is a native Android prototype for managing network access for apps on a device. It currently includes a launchable-app list, persistent block selections, a consent-gated experimental VPN packet-discard service, schedules, temporary blocks, focus modes, local user profiles, local change history, opt-in live speed, local usage history, usage analytics, and a Supabase account/backup prototype. Traffic blocking and background timing/statistics have not been verified on physical devices; the UI labels enforcement as unverified. Supabase authentication and backup are not verified until configured against the project.

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

Temporary blocks start immediately for selected apps, support concurrent timers up to seven days, show remaining time, and can be canceled early. Expiry restores the current underlying decision. Temporary rules take precedence while active. Expiry alarms use the same inexact scheduler, so Android may let a temporary block run past its configured end; VPN traffic blocking and timer delivery have not been validated on a physical device.

## Focus modes

Focus modes are locally stored named groups of launchable apps. Activate one group at a time to add those apps to the block set. Study, Work, and Sleep are suggested editable names. Profiles and schedules can override focus rules; temporary blocks remain higher priority. Enforcement still requires VPN consent and physical-device validation.

## User profiles

Profiles are stored locally and can be created, edited, deleted, activated, or deactivated. A profile defines explicit blocked and allowed app sets, with Work, Study, and Sleep offered as editable name suggestions. One profile can be active at a time. Effective state precedence is temporary block > active schedule > active profile > active focus mode > manual selection; blocking wins when schedules conflict. Profiles represent local rules only and do not confirm that VPN traffic is being blocked.

## Change history

The history screen records manual block/allow requests (including requests overridden by higher-priority rules) and effective per-app transitions caused by timers, schedules, profiles, and focus modes. Entries include local timestamp, source, and enforcement state. It stores up to 500 events on-device and provides a clear-history action. The first observed state is a baseline rather than an action. VPN-active events remain explicitly unverified, and pending/inactive states do not claim that traffic was blocked. History has no export control and is not synchronized.

## Live network speed

The speed screen estimates aggregate device-wide download/upload rates from Android `TrafficStats` byte-counter deltas. It is approximate, includes all interfaces, and is not per-app usage. Unsupported/reset counters display no value. Sampling is user-started and can be set to 1, 2, 5, or 10 seconds (2 seconds by default); stop it from the screen or ongoing notification. The ongoing indicator runs as a user-started `specialUse` foreground service. Android 13+ notification permission is requested; if denied, monitoring still runs but the notification may be hidden from the drawer. This foreground service type requires platform/distribution policy review before a Play release. Counter behavior, notification visibility, and battery impact need real-device testing.

## Internet usage history

Usage history requires the user to grant Android Usage Access in Settings. After the user starts collection, the app queries per-UID Wi-Fi and mobile usage summaries with `NetworkStatsManager` in the background about every six hours, plus on manual capture. Completed daily windows are stored in local SQLite and used for today, current month, and since-collection-start totals. Collection starts at opt-in; older device statistics are not imported. Visible apps that share a UID are grouped; hidden/unrecognized UIDs and non-Wi-Fi/mobile transport data are not shown. A persistent background job can be delayed, and catch-up after long downtime is capped at 30 days with a visible gap warning. The user can pause collection or clear local history. Access revocation and unsupported/error responses add no records. Device/OEM coverage and attribution need physical-device validation.

## Usage analytics

Analytics ranks the top visible apps/UID groups for today, this month, or all captured history, and displays a seven-day trend for dates with recorded coverage. The bar lengths are relative within the current view; exact totals appear in text for accessibility. Days without captured windows are omitted, and partial coverage is not described as a complete day. Analytics uses the same source, local history, and limitations as the Usage History screen.

## Accounts and cloud backup (Feature 10 prototype)

The account screen uses Supabase Auth REST endpoints with the Android client's public publishable key only. Session tokens are encrypted with AES-GCM using a key held by Android Keystore. Create-account, sign-in, sign-out, and password-recovery requests are implemented but have not been tested against a configured Supabase project. Core app data remains local when signed out or offline.

Cloud backup is off by default and is manual after explicit opt-in. It includes selected app package IDs and active selection metadata, profiles, schedules, and focus modes. It never includes usage history, analytics, passwords, VPN consent, or Android special-access grants. Each sync compares local and cloud snapshots and asks the user which copy should replace the other; it never silently merges or overwrites. Backup format version 1 is stored in settings. Restore re-evaluates schedules locally and cannot restore system permissions.

To configure local development, copy `supabase.properties.example` to the repository root as `supabase.properties` and fill in the HTTPS project URL and publishable key from Supabase project API settings. The local file is gitignored. Do not put a `service_role` key, password, or token there. The project URL was not present in this checkout, so account networking currently reports **Not configured**. In Supabase, apply `supabase/migrations/202611020001_user_backups.sql`, then run its pgTAP policy suite from `supabase/tests/user_backup_rls.test.sql` against a disposable project/database. Owner access policies are enabled for every app-prefixed backup table; the atomic `app_replace_my_backup` RPC uses `SECURITY INVOKER` and derives ownership from `auth.uid()`.

Password recovery and email confirmation use the `appinternetcontrol://auth-callback` deep link. Add that exact redirect URL to the Supabase Auth redirect allow-list and use email templates that preserve Supabase's callback tokens; then verify sign-up confirmation and password recovery against the project. Account deletion is not exposed in the app: Supabase user deletion requires a separately reviewed server-side function and an explicit product deletion flow. Neither backend setup nor server-side account deletion has been run by this repository change.

## Prerequisites

- Android Studio with a compatible JDK. This foundation uses Android Studio's bundled JBR 25; Gradle 9.6 supports running on JDK 25 and AGP 9.4 requires JDK 17 or newer.
- Android SDK Platform 37 and SDK Build Tools 36.0.0.
- Android SDK Platform Tools for ADB and a connected device for on-device testing.

Build versions are pinned to Android Gradle Plugin 9.4.0, Gradle Wrapper 9.6.0, and AGP built-in Kotlin 2.2.10. AGP 9.4 supports API 37 and specifies Gradle 9.6.0 and Build Tools 36.0.0 as its defaults.

The repository does not track `local.properties`, SDK paths, signing files, credentials, or other machine-specific configuration. Android Studio can use its configured SDK path; for command-line builds set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your installed SDK directory if Gradle cannot locate it.

The Supabase client config is separate: copy `supabase.properties.example` to `supabase.properties` only on a developer machine. Never commit it. The app uses only the publishable client key and cannot perform administrative user deletion.

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

# App Internet Control

App Internet Control is a native Android prototype for managing network access for apps on a device. It currently includes a launchable-app list, persistent block selections, a consent-gated experimental VPN packet-discard service, schedules, temporary blocks, focus modes, local user profiles, local change history, opt-in live speed, local usage history, usage analytics, and Supabase Auth/cloud-backup support. Traffic blocking and background timing/statistics have not been verified on physical devices; the UI labels enforcement as unverified. Supabase networking is not verified until configured and tested against the project.

## Approved Android levels

- Minimum SDK: API 29 (provisional pending VPN and scheduling validation on physical devices)
- Compile SDK: API 37
- Target SDK: API 37
- Initial physical-device test matrix: API 29, API 34, and API 37/current Android; test Wi-Fi and mobile data where available and multiple OEMs when practical.

Routing selected apps through `VpnService` and dropping their traffic is implemented as a prototype, but remains an unvalidated hypothesis until real-device tests pass.

## Installed-app list and prototype blocking

The list uses Android's launcher-intent package-visibility query, so it shows launchable apps, not necessarily every installed package. The app does not request `QUERY_ALL_PACKAGES`. Per-app selections are stored locally on the device. Enabling them asks Android for VPN consent. On Android 13 and newer the app asks for notification permission to show the ongoing VPN status in the notification drawer, but continues if that optional permission is declined. The prototype routes only selected visible packages through a local VPN interface and discards packets read there. Until tested on physical devices, this must not be treated as verified traffic blocking.

## Schedules

Schedules support one-time dates and weekly weekday rules with local start/end times and selected launchable apps. Start/end controls use a 12-hour picker with AM/PM; values are stored as normalized minutes since midnight. Overnight windows stay active across midnight and end at an exclusive local-time boundary. One shared boundary alarm is recalculated after edits, alarm delivery, boot, clock/time-zone changes, app replacement, and every VPN service start/update. The VPN service reloads and evaluates persisted rules instead of trusting a potentially stale start intent, so missed boundaries are reconciled when rules are next refreshed. An ending schedule removes only its own contribution: manual and other active rules are resolved again together.

On Android 12+, the app requests the user-controlled **Alarms & reminders** special access after a schedule is saved or enforcement is enabled. Granting that access triggers alarm recalculation. When access is granted, the shared boundary alarm uses `setExactAndAllowWhileIdle`; without it, an inexact alarm is retained as a fallback and Android may delay the transition. VPN enforcement still requires Android VPN consent, and the OS/OEM may restrict background service starts or force-stopped apps. Alarm timing and VPN rule transitions remain pending physical-device validation.

The schedule app picker shows each app's launcher icon and name, filters names without case sensitivity, and keeps choices independent of the current search results. A no-match state is shown when the query has no results. Search, selection state, and empty-state behavior have unit tests; visual interaction on the updated build is pending the next unlocked-phone test.

Temporary blocks start immediately for selected apps, support concurrent timers up to seven days, show remaining time, and can be canceled early. Expiry restores the current underlying decision. Temporary rules take precedence while active. Expiry alarms use the same inexact scheduler, so Android may let a temporary block run past its configured end; VPN traffic blocking and timer delivery have not been validated on a physical device.

## Focus modes

Focus modes are locally stored named groups of launchable apps. Activate one group at a time to add those apps to the block set. Study, Work, and Sleep are suggested editable names. Profiles and schedules can override focus rules; temporary blocks remain higher priority. Enforcement still requires VPN consent and physical-device validation.

## User profiles

Profiles are stored locally and can be created, edited, deleted, activated, or deactivated. A profile owns only its explicit blocked and allowed app package sets; the active profile ID is stored separately. Switching or deleting a profile does not edit manual selections, schedules, focus modes, or temporary-block records. Effective state precedence is temporary block > active schedule > active profile > active focus mode > manual selection; blocking wins when schedules conflict. Profile allow rules remove matching lower-priority manual/focus blocks while the profile is active, and those lower-priority records take effect again when the profile is deactivated or deleted. Profiles represent local rules only and do not confirm that VPN traffic is being blocked.

## Change history

The history screen records manual block/allow requests (including requests overridden by higher-priority rules) and effective per-app transitions caused by timers, schedules, profiles, and focus modes. Entries include local timestamp, source, and enforcement state. It stores up to 500 events on-device and provides a clear-history action. The first observed state is a baseline rather than an action. VPN-active events remain explicitly unverified, and pending/inactive states do not claim that traffic was blocked. History has no export control and is not synchronized.

## Live network speed

The speed screen estimates aggregate device-wide download/upload rates from Android `TrafficStats` byte-counter deltas. It is approximate, includes all interfaces, and is not per-app usage. Unsupported/reset counters display no value. Sampling is user-started and can be set to 1, 2, 5, or 10 seconds (2 seconds by default); stop it from the screen or ongoing notification. The ongoing indicator runs as a user-started `specialUse` foreground service with a distinct, low-importance `Live network speed` channel and persistent updating notification. Android 13+ notification permission, app notifications, and this channel must be enabled for monitoring to run. If visibility is denied before start, monitoring does not start; if disabled while running, the service stops and clears its running state. This foreground service type requires platform/distribution policy review before a Play release. Counter behavior, notification visibility, and battery impact need real-device testing.

### Status-bar speed text

A regular Android notification can show its small icon in the status bar; its changing text is shown in the notification shade, not as arbitrary status-bar text. Android 15/API 35 has no public app API for writing a live speed string into that area. Android 16/API 36 adds promoted Live Update status chips, but promotion requires the Live Updates permission and system eligibility, can be disabled by the user/OEM, and is not a guaranteed always-visible speed meter. The connected realme RMX3834 runs Android 15/API 35, so that API is unavailable there. Realme documents a separate system-owned real-time speed toggle on supported UI editions; its support guidance says UI S/T/R/Go/U editions do not support that toggle. The current build fingerprint is on the `T` branch, but the exact UI edition/toggle should be confirmed manually in Settings. App Internet Control cannot enable or write to that OEM display. The persistent, low-importance foreground notification remains the supported app-controlled fallback and updates the same notification at the selected sampling interval.

## Internet usage history

Usage history requires the user to grant Android Usage Access in Settings. After the user starts collection, the app queries per-UID Wi-Fi and mobile usage summaries with `NetworkStatsManager` in the background about every six hours, plus on manual capture. Completed daily windows are stored in local SQLite and used for today, current month, and since-collection-start totals. Collection starts at opt-in; older device statistics are not imported. Visible apps that share a UID are grouped; hidden/unrecognized UIDs and non-Wi-Fi/mobile transport data are not shown. A persistent background job can be delayed, and catch-up after long downtime is capped at 30 days with a visible gap warning. The user can pause collection or clear local history. Access revocation and unsupported/error responses add no records. Device/OEM coverage and attribution need physical-device validation.

## Usage analytics

Analytics ranks the top visible apps/UID groups for today, this month, or all captured history, and displays a seven-day trend for dates with recorded coverage. The bar lengths are relative within the current view; exact totals appear in text for accessibility. Days without captured windows are omitted, and partial coverage is not described as a complete day. Analytics uses the same source, local history, and limitations as the Usage History screen.

## Accounts and cloud backup (Feature 10 prototype)

The account screen uses Supabase Auth REST endpoints with the Android client's public publishable key only. Session tokens are encrypted with AES-GCM using a key held by Android Keystore. Create-account, sign-in, sign-out, and password-recovery requests are implemented but have not been tested against a configured Supabase project. Expired/revoked sessions show a sign-in-again error while core app data remains local and usable offline.

Manual cloud backup is off by default and requires explicit opt-in. It includes selected app package IDs and active selection metadata, profiles, schedules, and focus modes. It never includes usage history, analytics, passwords, auth tokens, VPN consent, or Android special-access grants. Each manual sync compares local and cloud snapshots and asks which copy should replace the other. Restore validates backup schema version 1 before writing and re-evaluates schedules locally; Android permissions are not restored.

Automatic backup is separately enabled per Supabase account and supports daily or weekly best-effort scheduling. No automatic upload runs unless the user is signed in, has configured the project URL/publishable key, and enables the setting. The first empty-cloud backup can upload after opt-in. Thereafter, automatic sync uploads local-only changes only when the remote copy matches the last-sync baseline. If the cloud changed on another device, automatic sync records a conflict and leaves both copies untouched for manual review. The app verifies uploaded content before updating last-success time. Supabase Auth sessions are encrypted with AES-GCM using Android Keystore; neither the password nor auth tokens are included in backup payloads. Local app controls remain available offline or signed out. JobScheduler can defer background work. Live sync, timing, error handling, and restore have not been tested against a configured project or on a physical device.

To configure local development, copy `supabase.properties.example` to the repository root as `supabase.properties` and fill in the HTTPS project URL (`https://<project-ref>.supabase.co`) and public publishable/anon key from Supabase Project Settings → API. This local file is gitignored. Never put a `service_role`/secret key, dashboard password, or personal access token there. This checkout has no `supabase.properties`, so account networking and cloud backup currently report **Not configured**. For a local-only stack, run the migrations in order and execute `supabase/tests/user_backup_rls.test.sql` against the disposable local database before considering any hosted rollout. Configure Auth email confirmation/recovery and allow-list the exact redirect `appinternetcontrol://auth-callback`. RLS is enabled for all four tables, anonymous access is denied, and authenticated users are limited to their own rows by `auth.uid()`. Writes use the transactional `app_replace_my_backup` RPC; reads use `app_read_my_backup` in a single statement under caller RLS. This design uses Postgres tables and RPCs; it requires no Supabase Storage bucket. Keep only the publishable client key in the Android app; never expose a service-role key.

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

## Local Supabase verification

The migrations in `supabase\migrations` and the pgTAP test in
`supabase\tests\user_backup_rls.test.sql` are intended for a local Supabase
stack only:

```powershell
supabase start
supabase test db
```

Local verification is currently blocked on this machine because Docker
Desktop's Linux engine is not running and the Supabase CLI is not installed.
No hosted Supabase credentials were used, and no hosted project was changed.

## Phase 11 physical-device status

A partial smoke test was performed on an authorized realme RMX3834 running Android 15/API 35. The debug app launched, the installed-app list showed app names/icons, the VPN service started/stopped with Brave selection, and the live-speed foreground notification appeared and was stopped. These observations do not verify that selected-app traffic is blocked.

The one-time schedule ended 56 seconds after its foreground boundary in one run. In a background run it remained active more than 54 seconds after expiry until foreground reconciliation. Actual network filtering, Usage Access/statistics, temporary-block expiry, profile behavior, and the approved API 29/34/37 device matrix remain unverified. See [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) for the checklist and open device issue. Supabase remains **Not configured**.

## Project documents

- [Master plan](MASTER_PLAN.md) — requirements, approved platform levels, risks, and acceptance criteria.
- [Development plan](DEVELOPMENT_PLAN.md) — staged implementation roadmap.

## Interface and launcher appearance

The app uses a shared light palette with blue and teal accents, accessible text colors, and native Android controls. The launcher uses original vector adaptive-icon assets built around a speedometer, shield, and interrupted-network mark. The assets compile successfully; launcher appearance and screen polish still need review on the physical phone.

Android controls VPN status. While a VPN service is active, Android displays the system VPN key/status indicator, and the VPN foreground service must provide its own ongoing notification. The VPN uses a separate low-importance channel; Android still places that notification in the shade. Low is the least intrusive supported importance for a foreground-service notification; `MIN` is explicitly discouraged for foreground services. The app cannot safely remove or hide the VPN notification while keeping the service in the foreground. Android may hide foreground-service notices from the drawer if the user denies notification permission, while surfacing the running app in Active apps/Task Manager; the app does not use permission denial or channel blocking to suppress its VPN notice. The live-speed foreground notification is separate and may appear alongside it. See the [Android VPN user-experience documentation](https://developer.android.com/develop/connectivity/vpn), [foreground-service notification guidance](https://developer.android.com/develop/background-work/services), and [notification importance reference](https://developer.android.com/reference/android/app/NotificationManager#IMPORTANCE_LOW).

## Latest offline verification — 2026-11-03

The updated code passes 68 unit tests and `assembleDebug`. The debug APK is 3,358,408 bytes (about 3.20 MiB); DEX files are the largest APK entries. A pre-change APK was installed on the authorized realme RMX3834 / Android 15 (API 35), but the phone was locked and then disconnected before the updated UI could be inspected or installed. The revised picker, status refresh, theme, and launcher icon therefore still need interactive device verification. No release APK was created. The Android Studio JBR was used only in the build process; no machine-wide environment variables were changed.

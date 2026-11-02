# Development Plan

`MASTER_PLAN.md` is the authoritative source for product requirements, technical decisions, risks, and acceptance criteria. Read it before implementing features.

## Stage 1 — Environment and project setup

- Confirm the supported Android versions and development toolchain.
- Prepare the repository, documentation, and build environment.
- Review Android platform capabilities and constraints for per-app network control.

## Stage 2 — Product and technical design

- Define the initial user flows and accessibility expectations.
- Select an Android architecture and UI approach.
- Document permission, privacy, security, and platform behavior decisions.

## Stage 3 — Application foundation

- Create the native Android project and baseline build configuration.
- Add navigation, theme, and app list presentation.
- Establish code quality and automated verification workflows.

## Stage 4 — Internet access management

- Implement app discovery and per-app access settings using supported Android APIs.
- Explain required consent and system dialogs to users.
- Handle unsupported devices, permission changes, and service lifecycle events.

## Stage 5 — Validation and refinement

- Verify behavior on representative Android versions and devices.
- Review accessibility, privacy, reliability, and edge cases.
- Improve usability based on test findings.

## Stage 6 — Release preparation

- Prepare release signing and distribution configuration outside source control.
- Document installation, supported behavior, and known limitations.
- Produce and validate a release build.

## Current status

Features 1–9 include app controls and local rule/history features, opt-in live speed and usage collection, and usage analytics. Effective rule precedence is temporary block > active schedule > active profile > active focus group > manual selection; block wins among conflicting schedules. Schedule/timer evaluation includes local wall-clock boundaries and daylight-saving behavior. History retains up to 500 local events with source and unverified outcome labels. Speed uses approximate device-wide `TrafficStats` deltas at a user-configurable 1/2/5/10-second cadence with a stoppable user-started foreground notification. Usage history requires user-granted Usage Access; a persisted six-hour job and manual capture query Wi-Fi/mobile per-UID summaries into local SQLite, then aggregate daily, monthly, and since-collection-start totals. Analytics ranks top visible app/UID groups and graphs only recent days with captured coverage. Collection gaps, shared UIDs, scope, and history start are disclosed. Feature 10 now has a Supabase account screen, Auth REST requests, email callback/password recovery support, AES-GCM/Android Keystore session persistence, opt-in manual backup/restore for settings/profiles/schedules/focus modes, explicit conflict direction, a transactional owner-scoped Postgres migration/RPC, and pgTAP RLS tests. Usage history is deliberately excluded. Backend URL/key were not present locally, so live Auth, network backup/restore, database migration, pgTAP policy execution, recovery redirects, and account deletion remain unverified/manual. API 29 remains provisional, and the API 29/34/37 device matrix is still pending.

### Phase 11 partial physical-device report — 2026-11-03

**Device:** authorized realme RMX3834 / Android 15 (API 35) / arm64-v8a. Only this device was connected; this does not cover the approved API 29, 34, and 37/current matrix.

- **PASS:** debug APK installed and `MainActivity` launched; no app fatal exception was found in the inspected startup log window. The installed-app screen displayed launchable app names/icons and Brave's package entry.
- **PASS:** Android VPN consent was already granted, so no new consent dialog appeared. Selecting Brave started the VPN foreground service; clearing it stopped the service and left the persisted selection empty. This proves the service lifecycle only, not traffic filtering.
- **PASS:** live-speed monitoring showed its running state, a foreground notification, and a 1-second selected cadence. It displayed 0 B/s in the sample window; stopping removed the speed service. Counter accuracy and screen-off survival were not tested.
- **PARTIAL:** one-time schedule for Brave became active when saved after its start time. In the foreground, the end transition was observed by 00:10:56 for a 00:10 boundary (56 seconds late), and the VPN service stopped. In the background, the 00:14 boundary remained pending and the VPN service remained active at 00:14:35; bringing the app task forward triggered reconciliation and stopped it by 00:14:54. The scheduler uses inexact alarms, and background expiry was not timely in this observation.
- **PARTIAL / BUG FOUND:** a temporary focus mode could be created, activated for Brave, and deactivated; the VPN service started/stopped accordingly. The focus screen remained stale after service state changes. A status-broadcast listener and regression tests were added in the offline continuation below; device recheck remains pending.
- **BLOCKED:** actual selected-app traffic blocking and unselected-app connectivity were not verified. A URL-based browser check was rejected by the environment's automatic review. Do not infer network enforcement from the active VPN interface or UI state.
- **NOT TESTED:** temporary-block cancellation/expiry/restart; profiles and precedence; Wi-Fi versus mobile traffic; DNS, IPv4/IPv6, and another VPN conflicts; notification behavior after permission denial; speed while screen-off; Usage Access, manual usage capture, and JobScheduler; Supabase account/backup (still **Not configured**); API 29/34/37 and other OEMs.

Temporary test schedules and the temporary focus mode were deleted; the manual selection was empty and live-speed monitoring was stopped at cleanup. Forty-two unit tests pass and `assembleDebug` succeeds with the lifecycle and main-screen UI fixes in this checkpoint. The speed service's `specialUse` type still requires Play review.

### Phase 11 continuation — offline fixes and verification — 2026-11-03

The repository was clean at `45b0f8dddfd7b0f51c5d11a4fa5596ce9c15f037`, identical to the fetched `origin/main` tip before work began. The authorized device was identified as realme RMX3834, Android 15/API 35, arm64-v8a, with approximately 74 GB free at the time checked. ADB was available from the configured SDK's `platform-tools` even though it was not on the shell PATH. Android Studio JBR 25.0.4 was used for builds in a temporary PowerShell process environment because Java was not on PATH; no global environment settings were modified.

- **Implemented, automated checks passed:** schedule app selection now uses a searchable list with launcher icons, case-insensitive name filtering, a no-results state, and selection kept by package ID across filtering. Existing selections load into the editor's temporary picker state and are applied only when the dialog is confirmed.
- **Fixed, automated regression coverage added:** Focus Mode now listens for the VPN service's status-change broadcast and refreshes its status when the service starts/stops. The previous stale-status behavior is awaiting device recheck.
- **Implemented, build verified:** a shared color/theme pass and original vector adaptive launcher icon assets were added. The updated appearance and launcher icon have not been inspected on-device.
- **PASS:** 45 unit tests; `assembleDebug`.
- **PASS:** APK packaging declares minSdk 29, targetSdk 37, compileSdk 37. Debug APK size is 3,358,408 bytes (about 3.20 MiB), with DEX files the largest entries.
- **PARTIAL:** an earlier build was installed and MainActivity was launched while the authorized device was connected. The phone showed its lock screen during the attempted visual capture. Per the user's instruction the phone was then disconnected; the updated build has not been installed or interacted with on-device.
- **NOT TESTED / STILL PENDING:** schedule search and icon display, focus status refresh, launcher icon, and all remaining Phase 11 interactive checks on the updated APK; actual packet filtering; background timer reliability; Wi-Fi/mobile, DNS/IPv4/IPv6 behavior; Usage Access and statistics; speed notification during VPN/screen-off; and API 29/34/37 plus other OEMs. Supabase remains **Not configured**.

The system VPN status icon is controlled by Android and cannot be hidden by this app. The optional speed-monitor notification is a separate app notification. The app must not attempt to spoof or suppress the system indicator.

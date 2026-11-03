# App Internet Control — Master Project Plan

**Status:** Features 1–10 are implemented as prototypes. Phase 11 has partial results from one realme Android 15/API 35 device. Offline UI refinements include a searchable icon-bearing schedule app picker, reactive VPN status on the Focus Mode screen, a shared color theme, and vector adaptive launcher icon assets; these changes await device review. Actual traffic filtering, reliable background alarm timing, usage statistics, and the approved API 29/34/37 device matrix remain unverified.
**Authoritative requirements:** This document is the single source of truth for product requirements, technical decisions, risks, and acceptance criteria. Update it when requirements or validated platform behavior changes.
**Repository:** [itsmebillah/app-internet-control](https://github.com/itsmebillah/app-internet-control)

## 1. Project Overview

App Internet Control is a native Android application intended to let a device owner choose installed applications whose internet traffic should be blocked, without root access. Other applications should retain normal internet access. The product also aims to offer schedules, temporary blocking, profiles and focus modes, network indicators, usage history and analytics, and optional account-backed backup while keeping core controls available offline.

Selective blocking is a technically demanding goal, not an established capability of this repository. Android does not expose a general third-party API to toggle another app's Wi-Fi and mobile-data permissions. A user-consented local `VpnService` is the principal candidate. The service must actually process and filter traffic; simply creating a VPN interface or configuring Android's per-app VPN lists does not provide a firewall. Feasibility and behavior must be demonstrated on physical devices before the product promises reliable blocking.

## 2. Complete Feature List

### A. Per-app Internet Control

- List installed applications, with understandable app names/icons and suitable filtering/search.
- Let the user select individual applications and provide a clear per-app On/Off control.
- Block selected apps over Wi-Fi and mobile data; preserve normal connectivity for unselected apps.
- Make current VPN/service state and any platform limitation visible.
- Keep the selected set durable locally and rebuild the VPN configuration when necessary.
- Do not represent an app as blocked when the filtering service is stopped, revoked, bypassed, or unable to enforce the rule.

### B. Automatic Scheduling

- Schedule a selected block state to start and end, including recurring daily schedules and chosen weekdays.
- Support one-time and multiple schedules, clear time-zone and daylight-saving behavior, conflict resolution, and visible next transition.
- Restore and reconcile schedules after device restart, app upgrade, time change, permission change, or service restart where Android permits.
- Handle Doze, battery restrictions, force-stop behavior, exact-alarm access where justified, and OEM differences. Communicate timing guarantees honestly.

### C. Temporary Blocking

- Block chosen applications for a user-selected duration.
- Restore the prior effective state when the timer expires, while reconciling any profile or schedule that became active meanwhile.
- Persist the deadline and recover it after process death/restart as platform behavior permits.

### D. Focus Mode

- Group apps into a focus mode and activate it with one action.
- Offer suggested Study, Work, Sleep, and other activities while allowing users to customize groups.
- Define how a focus mode interacts with individual switches, schedules, profiles, and temporary blocks.

### E. User-Created Profiles

- Create, customize, edit, delete, and switch user-created profiles, including Work, Study, and Sleep examples.
- Apply a profile with one action and show which profile is active.
- Define deterministic behavior for overlapping profile, manual, temporary, and scheduled rules.

### F. Schedule History

- Record requested block/unblock actions, their source (manual, timer, schedule, or profile), timestamps, and whether enforcement succeeded.
- Show history in a useful, privacy-conscious view.
- Distinguish an action request from verified enforcement; do not log success until service state supports that conclusion.

### G. Live Network Speed

- Show current aggregate download and upload speed in the app.
- Provide a compact live indicator in the Android notification area when the user enables it.
- Refresh periodically at a configurable, battery-conscious cadence and stop collection when no longer needed.
- Respect notification permission behavior and foreground-service/background execution rules for the selected Android versions.
- Clearly label whether a reading is device-wide, VPN-observed, or another approximation; do not imply per-app precision unless verified.

### H. Internet Usage History

- Show per-app network consumption, including download and upload where available.
- Present daily, monthly, and lifetime totals and preserve the app's own collected records across restarts.
- Explain that locally collected history begins when collection starts; Android-provided historical statistics may have different coverage, aggregation, access, or accuracy.
- Explain missing data and permission limitations rather than filling gaps with invented values.

### I. User Accounts

- Support account creation, login, and logout if a secure backend is selected.
- Sync only explicitly opted-in app settings, profiles, schedules, and focus modes; usage history and analytics remain device-local by product decision.
- Back up and restore supported data; retain offline local controls when signed out or offline.
- Require an explicit upload/restore choice for conflicts and never sync Android permission grants.
- Define and verify account recovery, account deletion, and handling of unsupported/local-only records before launch.

### J. Usage Analytics

- Summarize highest-consuming apps and daily, monthly, and lifetime usage.
- Format data in MB/GB and use accessible charts or summaries where they help.
- Identify the source and coverage period for reported data and keep analytics consistent with usage-history limitations.

## 3. Technical Architecture

### Proposed structure

Use Kotlin with a layered, testable Android architecture:

- **Presentation:** Native Android UI (Compose is a candidate; select after project foundation), screen state and navigation.
- **Domain:** Explicit models and use cases for effective block state, schedule evaluation, profile precedence, usage summaries, and account operations.
- **Data:** Repository interfaces with local database/preferences and optional remote implementation.
- **Platform services:** A narrowly scoped `VpnService`, schedule receivers/work, network/usage API adapters, and notification handling.
- Use coroutines and `Flow` for asynchronous state. Room is a candidate for structured schedules, profiles, history, and locally collected usage. DataStore is a candidate for small settings. These are recommendations, not installed dependencies or finalized decisions.
- Keep platform APIs behind interfaces where useful so rule evaluation and data calculations can be tested independently.

### VPN and selective filtering (critical feasibility gate)

Android's `VpnService` requires explicit user consent through the system VPN flow. The VPN builder can establish either an allowed-application list or a disallowed-application list. With an allowed list, listed apps use the VPN and the rest use normal system networking; with a disallowed list, excluded apps use normal networking. Therefore, putting apps intended to be blocked into `addDisallowedApplication()` would bypass the VPN and would not block their network access. Android documents these routing semantics in its [VPN guide](https://developer.android.com/develop/connectivity/vpn) and [VpnService.Builder API](https://developer.android.com/reference/android/net/VpnService.Builder).

**Unvalidated hypothesis:** route only selected apps intended to be blocked through a local VPN, then drop their packets. The current prototype implements an allowlist VPN interface and discards packets read from its TUN descriptor, but this behavior has not been tested on a physical device and is not yet a validated product architecture. Routing, packet attribution/filtering, allowed-app connectivity, and real-device behavior must be validated before claiming reliable blocking. Any packet-processing stack must be evaluated for maintenance, licensing, security, performance, and protocol coverage before adoption.

The prototype uses Android's user-consented `VpnService.prepare()` flow and declares the service with `BIND_VPN_SERVICE`. It requests `POST_NOTIFICATIONS` on Android 13+ to make the persistent VPN status visible in the notification drawer, but continues if that optional permission is declined. On Android 14+, the VPN service declares the `systemExempted` foreground-service type; this is permitted for VPN apps configured by the user through Android Settings. The UI always marks packet filtering as unverified until physical-device checks pass.

A VPN excludes some traffic by design and Android/device behavior can affect routing. Research and test app-bound network sockets, VPN coexistence (only one user VPN can generally be active), always-on/lockdown settings, captive portals, tethering/system traffic, IPv6, DNS-over-HTTPS, QUIC, roaming, and service revocation. Do not offer a bypass path for apps the user expects blocked. If filtering is unavailable, report enforcement as unavailable and apply a documented fail-safe policy rather than claiming a block. Rebuild the VPN when app routing lists or relevant selection changes require it.

### App discovery and package visibility

Android 11+ filters visibility of installed packages by default. The Feature 1 screen declares a launcher-intent `<queries>` filter and shows launchable apps visible through that query; it does not claim to list every installed package. It does not request `QUERY_ALL_PACKAGES`. If broader inventory is required later, Google Play treats this permission as sensitive and approval-restricted. See [package visibility](https://developer.android.com/training/package-visibility) and [declaring visibility needs](https://developer.android.com/training/package-visibility/declaring). Test actual returned packages on supported Android versions.

### Speed and usage measurement

- Aggregate live speed can be estimated from byte-counter deltas over a monotonic elapsed-time interval. `TrafficStats` counter availability/semantics and VPN-interface counters must be investigated; label scope, handle counter reset/wrap, and measure overhead/battery.
- `NetworkStatsManager` can query per-UID history, but queries may take seconds and belong on worker threads. Access to other apps' usage is protected by user-granted Usage Access in Settings (`PACKAGE_USAGE_STATS`); records can be aggregated and are not a guaranteed real-time feed. See the [NetworkStatsManager API](https://developer.android.com/reference/android/app/usage/NetworkStatsManager).
- VPN-observed counters are an alternative for data seen by this service, but they start with collection, may have attribution/overhead gaps, and should not be combined with Android counters without avoiding double-counting.
- Define exactly what daily/monthly/lifetime means (calendar zone, reset date, app removal/reinstall, UID changes, unavailable records) and validate against device settings/carrier observations.

### Scheduling and background work

Persist schedule definitions and compute the next state transition. Use `AlarmManager` for user-visible transition timing if justified, and use `WorkManager` for deferrable persistence/reconciliation tasks rather than precise minute-level transitions. Android may defer inexact alarms due to Doze and battery restrictions; WorkManager periodic work has a minimum 15-minute interval and flexible execution. Exact alarms require special access on relevant Android versions and may not be granted by default. See [Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms) and [exact alarm changes](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms).

Recalculate after boot, time/time-zone changes, app updates, permission revocation, and device state changes where supported. Plan for force-stop (scheduled work is not a promise of execution while the app is stopped), process death, battery saver, OEM restrictions, and the need to explain exact-alarm access. Do not promise perfectly reliable schedule execution on every device without test evidence.

The current implementation persists one-time and weekly weekday app rules as normalized local times, evaluates local device wall time (including overnight windows and `java.time` daylight-saving resolution), and uses one shared alarm for the next schedule/temporary-block boundary. Start/end alarms are recalculated after edits, alarm delivery, boot, clock/time-zone changes, app replacement, VPN service starts/updates, and exact-alarm access grants. The VPN service recomputes effective rules from persisted state on each start/update rather than trusting a potentially stale intent, so missed boundaries reconcile at the next refresh. On Android 12+, the app requests user-controlled `SCHEDULE_EXACT_ALARM` special access for `setExactAndAllowWhileIdle`; if access is denied or revoked, it falls back to `setAndAllowWhileIdle` and delivery can be delayed. The schedule UI uses a 12-hour AM/PM picker and exclusive end boundaries. Effective decision precedence is temporary block > active schedule > active profile > active focus group > manual selection. A profile's explicit allow/block decision overrides focus and manual state; a schedule overrides the profile; when active schedules conflict for one app, **block wins**. Temporary blocks override all underlying decisions and restore the current underlying decision at expiry. Android may still delay alarms, stop force-stopped applications, or deny a background VPN foreground-service start; those cases remain pending and must not be shown as enforced. This behavior awaits physical-device validation.

Focus modes are locally stored named groups of launchable app package IDs, with one active group at a time. Activating a mode adds its apps to the current block set. Profiles and schedules can override focus-mode blocks, while temporary blocks have higher precedence. Study, Work, and Sleep names are offered as editable starting suggestions.

User profiles are locally stored named rules with separate explicit block and allow package sets; one profile may be active. Work, Study, and Sleep are editable name suggestions. Profile decisions override the active focus group and manual state, schedules override profile decisions, and temporary blocks remain highest priority. Profile preferences stay local and do not require an account. Profiles provide rule state only: traffic enforcement remains dependent on VPN consent and physical-device validation.

Schedule History records manual block/allow requests (including when a higher-priority rule overrides them) and changes to effective per-app rules caused by temporary timers, schedules, profiles, or focus modes. Entries include source, timestamp, and enforcement state. The view retains at most 500 events and allows users to clear the log. A newly observed baseline is not presented as a user action. Since this VPN prototype cannot verify end-to-end enforcement, history reports overridden, pending/inactive, or active-but-unverified state and never claims a successful block. History is device-local and has no export or sync.

Live speed uses `TrafficStats.getTotalRxBytes()` and `getTotalTxBytes()` deltas to estimate aggregate device-wide rates across interfaces. It is explicitly approximate and not per-app usage; unsupported counters, resets, or invalid intervals produce no reading rather than a fabricated zero. Sampling is user-started and configurable at 1, 2, 5, or 10 seconds (2 seconds by default), and can be stopped in-app or from its ongoing notification. The continuous user-visible notification uses an Android `specialUse` foreground service because no more specific service type covers this ongoing network indicator; current Android guidance requires a declared subtype and Play Console review for this type. Android 13+ notification permission, app notifications, and the speed channel must be enabled before monitoring starts. The service stops and clears its running state if notification visibility is later disabled. Device-specific counter behavior, notification visibility, and battery impact remain pending physical-device testing. See [TrafficStats](https://developer.android.com/reference/android/net/TrafficStats), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), and [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

Internet Usage History requests the sensitive `PACKAGE_USAGE_STATS` access through Android Settings; the app does not grant it or access stats before the user enables it. After explicit local-collection start, a persisted `JobScheduler` job queries Android's per-UID `NetworkStatsManager` summaries for Wi-Fi and mobile intervals about every six hours, with a manual capture action. Queries run off the main thread, and completed per-day windows are stored in local SQLite; collection can be paused and history cleared. The UI reports today, current month, and lifetime totals only from captured windows, grouped by visible launcher package UID (shared visible packages appear as one group). Data begins at collection start, older Android history is not imported, unrecognized UIDs and non-Wi-Fi/mobile transports are omitted, and missed windows are disclosed. Catch-up is capped at 30 days after a long gap. Device/OEM data availability, UID mapping, job timing, and coverage must be validated on real devices. Android's API documents that usage stats require user-granted Usage Access and summary queries can take several seconds; see [NetworkStatsManager](https://developer.android.com/reference/android/app/usage/NetworkStatsManager), [PACKAGE_USAGE_STATS](https://developer.android.com/reference/android/Manifest.permission#PACKAGE_USAGE_STATS), and [Usage Access Settings](https://developer.android.com/reference/android/provider/Settings#ACTION_USAGE_ACCESS_SETTINGS).

Usage Analytics ranks the highest-usage visible app/UID groups for today, this month, or all locally captured history, and shows a recent seven-day trend only for days with captured coverage. The bars are relative comparisons; exact byte totals are displayed alongside them. Dates without captured windows are omitted, and partial-day captures are not presented as complete days. Analytics uses the same local records and coverage limitations as Usage History; it is not an independent or per-app live measurement.

Temporary blocks start immediately, persist selected package IDs and an absolute expiration instant locally, support concurrent timers, and can be canceled early. The app evaluates the expiry against wall-clock time and includes the next active expiration in the same inexact alarm scheduler. Late delivery can therefore extend a block; the UI warns about this limitation, and device timing remains unverified.

### Local storage and cloud synchronization

Keep core controls local and available offline. Approved backend: Supabase Auth + Postgres. The client uses only its public publishable key over TLS; its session is AES-GCM encrypted with an Android Keystore key. Backup is explicit opt-in and manual, limited to selected-app settings, profiles, schedules, and focus modes; usage history and analytics are excluded. The Supabase migration enables RLS on every table, limits rows to `auth.uid()`, and offers one transactional `SECURITY INVOKER` replacement RPC. Conflict handling requires the user to choose upload or restore. Cloud integration remains unverified until a project URL/key is configured, migrations/policies are applied and tested, email callbacks are configured, and device behavior is checked. Administrative account deletion remains a server-side/manual prerequisite; never place a service-role key in the app or repository.

## 4. User Interface

Expected screens and key information:

- **Home dashboard:** Current enforcement status, active profile/focus mode, quick controls, next scheduled transition, and concise speed/usage summary.
- **Installed application list:** Search/filter, app identity, current effective allow/block state, and per-app control.
- **Internet control:** VPN consent/status, selected apps, service errors, and a clear explanation of enforcement limitations.
- **Schedule management:** Create/edit recurring weekday and one-time schedules, conflict feedback, next transition, and exact-timing access status if needed.
- **Temporary blocking:** Choose apps, duration/end time, and active countdown.
- **Focus modes and profiles:** Create/edit groups, activate/switch, and see rule precedence.
- **Live speed:** Download/upload readout, scope, refresh setting, and notification indicator control.
- **Usage analytics:** App ranking, daily/monthly/lifetime summaries, coverage dates, and data source.
- **Schedule history:** Timestamped effective block/allow transitions, source, and honest enforcement state.
- **Account and cloud backup settings:** Sign-in state, sync consent/status, backup/restore, data export/deletion, privacy and retention controls.

All screens should support accessible labels, scalable text, clear empty/error/loading states, and truthful permission explanations.

## 5. Data Storage and Backup

### Store locally

- User-selected packages and desired states.
- Schedule definitions, recurrence/time-zone data, profile definitions, focus groups, and precedence metadata.
- Local action history and locally collected usage samples/summaries with collection start and source metadata.
- User display preferences, notification choices, and sync consent state.

### Optional synchronized data

- Only explicitly opted-in account settings, profiles, schedules, and focus modes. Usage history and analytics are excluded from cloud sync.
- Avoid uploading the full installed-app inventory by default; package lists and usage patterns are sensitive. Explain exactly what is synchronized and why.
- Keep authentication/session material out of ordinary plaintext preference storage; evaluate Android Keystore-backed storage for tokens. Do not store passwords.

### Backup/restore behavior

- Define encrypted transport and server-side protection, authenticated per-user access, conflict resolution, version compatibility, and account deletion.
- Restore user rules/settings independently from device-specific execution state and permissions. Re-request VPN consent and special access on each device; never assume backup restores grants.
- Preserve offline operation and make sync failures visible without blocking local controls.

## 6. Security and Privacy

- No root access is required or planned.
- Request only permissions and special access needed for a feature, explain each before its system prompt, and allow optional features to remain off.
- Installed-app inventory, network usage, and schedules can reveal sensitive behavior. Minimize collection, sync, retention, and diagnostic logs.
- Use a reputable authentication flow; do not collect or store account passwords in the app.
- Secure sync with TLS and server authorization; never embed private API keys, signing secrets, tokens, or passwords in source or committed configuration.
- Give users clear controls to view, export, clear, and delete locally stored and cloud-synced data.
- Document collection start, retention periods, backup scope, deletion effects, and known measurement gaps.
- Treat VPN packet processing as security-critical: minimize packet data retention, avoid payload logging, review dependencies, and test failure/revocation behavior.

## 7. Development Environment

Planned toolchain:

- Windows 11 workstation.
- VS Code with Codex for repository work.
- Kotlin for native Android development.
- Android Studio for SDK management, emulator/device tools, and build diagnostics.
- Android SDK Platform, Platform Tools (including ADB), Build Tools, and command-line tools selected for the project's compile/target SDK.
- Java JDK 21 is the current planned candidate, subject to Android Gradle Plugin/Kotlin compatibility verification when the project is created.
- Gradle Wrapper committed with the Android project; no unrelated global Gradle installation is required.
- Git and GitHub for version control and review.

**Verified environment snapshot (2026-11-02):** Prior setup checks reported Git 2.55.0, Temurin JDK 21.0.13+9, Android Studio Rabbit2 2026.2.2, and Android SDK Platform 36, Build Tools 36.1, Platform Tools 37.0.1, and command-line tools. Android Studio's first-run setup was not complete and no emulator was installed; SDK setup had a partial temporary download at that time. Recheck tool paths, licenses, SDK components, and Studio readiness before creating/building the Android project. GitHub CLI was not installed; Git Credential Manager was configured. These are a dated snapshot, not a guarantee about the current machine state.

## 8. Development Roadmap

| Stage | Work | Dependencies and exit evidence | Main risks |
|---|---|---|---|
| 1. Environment setup | Verify JDK/AGP compatibility, Android Studio, SDK, ADB, physical-device debugging, and repository workflow. | Clean verified toolchain and documented supported device/API test set. | Incomplete SDK setup, driver/device authorization, version mismatch. |
| 2. Create Android project | Create native Kotlin project, Gradle Wrapper, package/application identity, baseline build, architecture boundaries, CI/build checks. | Reproducible debug build and clean repository; no feature claims. | Toolchain compatibility, package visibility/distribution decisions. |
| 3. Implement basic UI | Establish navigation/theme/accessibility, app list presentation, state/error patterns, and local models. | UI verified on emulator/device with no false enforcement state. | App discovery requires package visibility policy decision. |
| 4. Implement and test per-app internet blocking | Prototype VPN consent/lifecycle, packet forwarder/filter, selected UID blocking, allowed-app forwarding, network transitions and failure states. | Physical-device proof for Wi-Fi and cellular with repeatable tests; document unsupported paths before proceeding. | Highest risk: forwarding correctness, VPN conflicts, protocol coverage, battery, OEM behavior. |
| 5. Implement scheduling and temporary blocking | Persist rules, calculate precedence/next transition, schedule timers, recover after reboot/time changes, expose timing permissions. | Tests for one-time/recurring schedules and delayed/denied execution behavior. | Doze, force-stop, exact alarm access, OEM limits. |
| 6. Implement profiles and focus mode | User-editable groups, one-tap activation, profile switching, deterministic precedence. | Profile/manual/schedule/timer interactions tested. | Ambiguous conflicting rules and usability. |
| 7. Implement live network speed | Define speed source/scope, low-overhead sampling, foreground/notification behavior. | Device-wide measurement compared with reference and battery impact reviewed. | Counter availability, notification/FGS restrictions, power use. |
| 8. Implement usage history and analytics | Evaluate Usage Access and VPN counters, persistence, summaries, charts, gaps and retention. | Accuracy/coverage documented on supported APIs and measured against references. | Delayed/aggregated system statistics, permission denial, UID changes. |
| 9. Implement user accounts and cloud backup | Supabase Auth + Postgres; opt-in settings/profile/schedule/focus backup, recovery, conflict resolution, and deletion policy. | Security review and offline, backup/restore, deletion tests. | Backend setup and policy execution, recovery configuration, deletion implementation. |
| 10. Test, debug, optimize, and prepare the APK | Run device/API/OEM matrix, performance/battery/security/accessibility checks, release signing outside Git, prepare release documentation and APK. | All acceptance criteria met or explicit limitations agreed; reproducible signed release artifact. | Store policy, signing/key management, device variability. |

The roadmap is sequential at major dependency gates: reliable block enforcement must be proven before presenting the rest of the product as controlling traffic. The backend and sync scope are selected; project configuration and server-side account deletion remain prerequisites for release. No APK should be prepared until the project owner authorizes implementation/release work.

## 9. Testing Plan

### Pending physical-device validation

- API 29, API 34, and API 37/current Android; at least one real phone and different OEMs where practical.
- On Wi-Fi and mobile data where available, verify a selected app cannot reach test endpoints and unselected apps retain access. Exercise IPv4 and IPv6, DNS, app-bound sockets, and switching networks.
- Verify VPN consent decline/revocation, another VPN replacing this one, service termination, and that the UI never reports a block when this VPN is inactive.
- Confirm the persistent status notification behavior with notification permission allowed and denied.

- **Build and compilation:** Debug/release variants, Gradle Wrapper reproducibility, clean build, dependency/license review, and supported JDK/AGP combinations.
- **Physical devices:** Representative Android versions, API levels, vendors, Wi-Fi/cellular chipsets, and at least one real phone; emulator testing supplements but does not replace network enforcement testing.
- **Wi-Fi and mobile data:** Toggle networks, switch Wi-Fi/cellular, roam/reconnect, airplane mode, captive portal, IPv4/IPv6, DNS, and metered/unmetered networks.
- **Selective blocking:** Confirm blocked apps cannot reach test endpoints on either transport; confirm allowed apps retain expected access; test background traffic, app-bound sockets, VPN conflicts, service death/revocation, and selection changes. Record limitations and avoid claiming coverage not tested.
- **Schedules/restart:** One-time, daily, weekday, overlapping schedules, time-zone/DST/time changes, reboot, process death, denied exact-alarm access, battery saver/Doze, and force-stop. Measure transition delay.
- **Background execution:** Notification denied/allowed, service start/stop, app standby, OEM power management, network change, and recovery after system kills/restarts processes.
- **Battery/performance:** Idle and active CPU/memory/battery impact, packet throughput/latency, sustained traffic, thermal behavior, and sampling-rate tradeoffs.
- **Data accuracy:** Compare speed and per-app totals with Android settings/other independent counters; test permissions denied, delayed data, app reinstall/UID change, rollover/time zones, and missing samples.
- **Accounts/cloud backup:** Offline use, login/logout, token expiry, multi-device conflicts, encrypted transport, restore permissions, server access control, export and deletion.
- **Privacy/security/accessibility:** Verify no secrets or packet payloads enter logs/build artifacts; inspect requested permissions and data retention; test accessible labels, text scaling, and clear consent/error flows.

## 10. Project Rules

- Read `MASTER_PLAN.md` before implementing any feature.
- Update `MASTER_PLAN.md` when a requirement changes or research validates/rejects an assumption.
- Update `README.md` when project setup or usage instructions change.
- Work in small, testable stages.
- Never claim a feature works before testing it.
- Never overwrite unrelated files.
- Never commit secrets.
- Check Git status before committing.
- Pull or reconcile remote changes safely.
- Never force push.
- Push completed, verified work to GitHub.
- Keep the main branch buildable whenever possible.

## 11. Open Questions and Limitations

These questions remain open and must be resolved with evidence; difficult features must not be silently dropped.

1. **Traffic forwarding implementation:** Build/maintain a packet stack or adopt a vetted library? Confirm license, security posture, TCP/UDP/DNS/IPv4/IPv6/QUIC behavior, performance, and maintenance.
2. **Coverage semantics:** Define exactly which app traffic is blocked, including bound sockets, system traffic, tethering, DNS-over-HTTPS, IPv6, always-on VPN/lockdown, app VPN conflicts, and apps excluded by Android. If a route cannot be controlled, explain it and evaluate a technically sound alternative.
3. **Fail-safe behavior:** If the VPN stops or is revoked, should the UI prioritize normal connectivity or prevent unfiltered traffic where Android's lockdown support and user choice allow it? Define behavior and communicate consequences clearly.
4. **Distribution channel and app discovery:** Play Store or another channel? Does the app-list core use case qualify for `QUERY_ALL_PACKAGES` under current Play policy, or can narrower queries satisfy it?
5. **Android support range (approved initial values):** `minSdk 29` (provisional pending real-device validation), `compileSdk 37`, and `targetSdk 37`. Initial physical-device matrix: API 29, API 34, and API 37/current Android; test Wi-Fi and mobile data where available and include different OEM devices when practical. API 29 remains provisional until VPN filtering and scheduling behavior are validated on devices.
6. **Schedule precision:** The prototype uses inexact alarms and discloses possible delay. Decide whether exact-alarm special access is needed and set an acceptable transition tolerance after device testing.
7. **Usage data source:** Prototype choice is `NetworkStatsManager` with explicit Usage Access, Wi-Fi/mobile collection windows, locally persisted summaries since the user starts collection, and visible-app UID grouping. Shared UIDs are grouped; unknown UIDs/other transports and missed windows are omitted and disclosed; catch-up is capped at 30 days. Validate attribution/coverage and revise if device tests show gaps.
8. **Speed scope and cadence:** Prototype choice is aggregate device-wide `TrafficStats` counter deltas with a 2-second default (user options 1/2/5/10 seconds), explicitly approximate and not per-app. It uses a user-started `specialUse` foreground service and an ongoing notification; notification denial and Play Console eligibility remain platform/distribution validation items. Revisit cadence and battery impact after physical-device testing.
9. **Rule precedence:** The prototype defines temporary block > active schedule > active profile > active focus group > manual selection; block wins when schedules conflict. Validate that this model is understandable and suitable through usability and device testing.
10. **Cloud provider and account model:** Selected Supabase Auth + Postgres. Confirm hosting region/cost and configure the `appinternetcontrol://auth-callback` redirect; define and implement a server-side account deletion path before release.
11. **Privacy/retention:** Cloud backup is opt-in and limited to settings, profiles, schedules, and focus modes; usage history/analytics remain local. Define retention, export/deletion, and diagnostic handling before release.
12. **Lifecycle edge cases:** Define behavior after reboot, app force-stop/uninstall/update, VPN revocation, app install/removal, permission revocation, and another VPN becoming active.
13. **Release/signing:** Define distribution, signing-key custody, privacy disclosures, store declarations, and release process outside source control.
14. **Automatic Supabase backup:** Use Supabase Auth and only the public publishable/anon client key in Android. Require sign-in and a separate explicit automatic-backup opt-in before scheduled upload. Store app settings, profiles, schedules, and focus modes in the configured Supabase project; exclude usage history, analytics, passwords, auth tokens, and device permissions. Enable RLS on every table with authenticated owner-only policies based on `auth.uid()`. Use an invoker-rights transactional write RPC and an owner-scoped single-statement read. Preserve conflict-aware manual sync: automatic sync may upload local-only changes only while the cloud still matches the last-sync baseline; report conflicts without overwriting another device's data. Verify migrations, pgTAP policies, session expiry, no-network handling, and restore on a configured project/device before treating this as reliable.

### Known platform constraints

- VPN consent is user-controlled and can be revoked; Android's per-app allow/disallow routing is not packet filtering.
- Broad installed-app visibility is privacy-sensitive and may need store approval.
- Per-app usage queries require special Usage Access and may be delayed/aggregated.
- Android background and alarm delivery is conditional; perfect schedule timing cannot be promised without qualifying constraints and device testing.
- Notifications and foreground execution have version-dependent permission and policy requirements.
- Android owns the active-VPN status icon and system VPN indication; the app's optional speed notification is separate and cannot replace or hide Android's VPN indicator.
- Cloud backup cannot restore Android system grants such as VPN consent or special access; each device must authorize those locally.

### Phase 11 device observations (2026-11-03)

On one authorized realme RMX3834 running Android 15/API 35, the debug app launched, the launchable-app list showed names and icons, and the VPN foreground service started for selected Brave and stopped when its selection was cleared. These observations verify service lifecycle only; they do not verify that app traffic was blocked or that unselected traffic remained available. A foreground one-time schedule end was observed 56 seconds late. With the app in the background, the end boundary remained pending and the VPN service remained active more than 54 seconds after the configured end; foreground reconciliation then stopped it. Treat background schedule timing as a known limitation requiring follow-up. The focus screen also continued to display a stale “VPN is not active” status after the VPN service had started. See `DEVELOPMENT_PLAN.md` for the partial checklist and remaining device tests.

## 12. Acceptance Criteria

The project is complete only when each requested capability is implemented, tested on supported physical devices, and its limitations are documented. A feature that cannot meet its reliability target must have an owner-approved alternative and clear in-app disclosure; it must not be represented as complete merely because a UI exists.

- Selected applications are demonstrably blocked from internet access over both Wi-Fi and mobile data on the declared supported-device matrix, including relevant IPv4/IPv6 paths.
- Unselected applications retain normal internet access under the same tests.
- VPN consent, revocation, service failure, conflicts, and unsupported routes produce truthful status and the documented fail-safe behavior.
- One-time and recurring schedules, selected weekdays, multiple schedules, temporary timers, and reboot recovery work within a documented timing tolerance under tested conditions; restrictions and denied-access behavior are clear.
- Live download/upload speed is visible in the app and notification area when enabled, with its scope labeled and battery cost reviewed.
- Per-app usage history is persisted and reviewable with daily/monthly/lifetime summaries, source/coverage labels, and known accuracy gaps disclosed.
- User-created profiles and focus groups can be created, edited, deleted, switched, and applied with tested precedence.
- Accounts can authenticate/logout and user-approved backup can be restored; core local controls work offline; export/deletion and security tests pass.
- User-authorized automatic Supabase backups can be disabled, are isolated by Auth/RLS per account, preserve local controls offline, expose last-success/errors, and refuse to overwrite concurrent remote changes; credentials and usage history are excluded.
- Android project builds reproducibly with the committed Gradle Wrapper, and physical-device tests pass for the documented support matrix.
- No secrets are committed; privacy, permissions, data retention, support range, and release behavior are documented.

## Reference Documentation

Primary Android documentation consulted for platform constraints (verify again when implementation decisions are made):

- [VPN guide](https://developer.android.com/develop/connectivity/vpn) and [VpnService.Builder API](https://developer.android.com/reference/android/net/VpnService.Builder)
- [Package visibility filtering](https://developer.android.com/training/package-visibility) and [declaring visibility needs](https://developer.android.com/training/package-visibility/declaring)
- [NetworkStatsManager API](https://developer.android.com/reference/android/app/usage/NetworkStatsManager)
- [Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms) and [Android 14 exact alarm changes](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)

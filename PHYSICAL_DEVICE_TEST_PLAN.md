# Physical-device validation plan

This plan is for the next interactive device session. No phone checks were run for the profile, UI, or Supabase automatic-backup changes in this checkpoint.

## Device matrix

| Tier | Android/API | Device coverage | Network coverage |
| --- | --- | --- | --- |
| Minimum | API 29 / Android 10 | At least one device | Wi-Fi; mobile data if supported |
| Middle | API 34 / Android 14 | At least one device | Wi-Fi and mobile data |
| Current | API 37 / Android 17 when available | At least one device | Wi-Fi and mobile data |
| Current available OEM | Realme RMX3834 / Android 15 / API 35 | Existing reference device; add another OEM when practical | Wi-Fi and mobile data |

Record manufacturer/model, Android build, API, ABI, available storage, Supabase sign-in state, and whether the OS granted VPN/notification/usage permissions. Do not treat one OEM/API result as coverage for another.

## Safety and setup

- Use a debug build reviewed by the owner; do not install it automatically from this checkpoint.
- Back up any test data first. Use harmless apps and non-sensitive profile names/package selections.
- The current VPN packet reader is still discard-only. **Do not test internet connectivity while routing apps through it.** Validate only consent, service start/stop, persisted effective rule set, and status reporting until a forwarding engine has been separately reviewed and implemented.
- Do not test Ad Blocker behavior; Ad Blocker work is paused and no domain filter is approved in this change.
- Capture `adb devices -l`, `getprop ro.build.version.release`, `getprop ro.build.version.sdk`, `getprop ro.product.manufacturer`, `getprop ro.product.model`, `getprop ro.product.cpu.abi`, and available `/data` storage before testing. Keep logcat scoped to the app and redact account/device identifiers before sharing.

## Master session run sheet

Do not run these install commands until the project owner approves installing the reviewed debug APK. Run commands from the repository root in PowerShell. Use the serial printed by `adb devices -l`; it can change between sessions.

```powershell
adb devices -l
$serial = "REPLACE_WITH_AUTHORIZED_DEVICE_SERIAL"
adb -s $serial get-state
adb -s $serial shell getprop ro.product.manufacturer
adb -s $serial shell getprop ro.product.model
adb -s $serial shell getprop ro.build.version.release
adb -s $serial shell getprop ro.build.version.sdk
adb -s $serial shell getprop ro.product.cpu.abi
adb -s $serial shell df -h /data
adb -s $serial install -r "app\build\outputs\apk\debug\app-debug.apk"
adb -s $serial shell am start -n com.itsmebillah.appinternetcontrol/.MainActivity
adb -s $serial logcat -d -v time -s AndroidRuntime:E
```

If ADB reports `unauthorized`, unlock the phone and accept its USB/Wireless debugging trust dialog yourself. If it reports `offline` or no device, reconnect it and repeat `adb devices -l`; do not bypass the device authorization dialog. `adb install -r` updates the package while retaining app data; verify any test data is disposable before using it.

For every check below, record **PASS**, **FAIL**, **NOT TESTED**, or **BLOCKED**, plus device/API, exact steps, observed result, and relevant redacted log. A visible toggle, active service, or saved rule is evidence only of app/UI state; it is not evidence that traffic was blocked.

## Ordered master checklist

1. **Install, startup, and UI:** install only after approval; launch; inspect all screens, empty/error/loading states, font scaling, TalkBack labels, navigation, and crash logs. Record each UI result separately.
2. **Sign-in, callback, and sign-out:** client-side PKCE logic and unit tests are implemented; **live callback testing remains BLOCKED** until local database tests pass and a disposable Supabase Auth project is configured. Signup and recovery attach an S256 challenge; the app accepts only a query authorization code on `appinternetcontrol://auth-callback`, consumes the encrypted saved verifier once, and exchanges the code. Password sign-in is a direct HTTPS password grant and does not redirect. Do not test with production accounts. On a disposable project, test signup confirmation, recovery/password update, session persistence, sign-in/out, and local controls while signed out.
3. **Backup opt-in, restore, and conflicts:** verify automatic backup starts off; enable only after sign-in; observe initial upload, last-success state, daily/weekly setting, manual upload/restore confirmation, schema rejection without local mutation, offline/error reporting, and retry. Change data on a second test client to exercise cloud-only and two-sided conflicts; verify neither copy is silently overwritten. Mark account isolation only after two distinct test users cannot read or write each other's rows through both pgTAP and authenticated API requests.
4. **Scheduled backup recovery:** while opted in, confirm the persisted job is present, exercise offline retry, force-stop/reopen, reboot, and observe actual run delay and last-success/error state. Disable backup and sign out; confirm no further upload is scheduled or performed. Android may defer periodic work; report measured delay rather than assuming exact timing.
5. **Profiles:** create two distinct profiles, edit, activate/switch, deactivate, delete with confirmation, and restart the process. Exercise profile allow/block against manual, schedule, focus, and temporary rules; confirm changing one source does not erase another source's saved rule.
6. **Schedules:** verify 12-hour AM/PM entry, 7:00 PM–9:59 AM overnight behavior, exact start/end boundaries, missed alarms, edits, reboot, time/time-zone changes, and VPN service restart. Record foreground and background delay separately.
7. **Temporary blocks and focus modes:** test creation, activation, cancellation/deactivation, expiry, restart/reboot, overlap with schedules/profiles/manual rules, and screen status refresh.
8. **VPN UI/service lifecycle only:** test consent grant/decline/revocation and start/stop state without using the VPN to judge internet access. The current VPN reader discards packets routed to its TUN; enabling it for a test app can interrupt that app's network. Keep actual traffic-blocking and unselected-app connectivity checks **BLOCKED** until a forwarding-capable engine is implemented and separately reviewed.
9. **Actual network enforcement (currently blocked):** only after the forwarding engine is approved, test a harmless test app on Wi-Fi and mobile data separately, with blocked and unblocked apps, IPv4/IPv6, DNS, network switches, service stop/restart, and schedule expiry. Capture independent evidence for the blocked app failing and an unselected app retaining connectivity. Never infer this result from UI or VPN status.
10. **Speed notification:** independently start/stop the speed monitor and VPN; verify the speed notification's updates, persistence, permission/channel behavior, screen-off/background behavior, and that stopping either service leaves the other running.

## Current session status

The device is not considered tested by this document. Fill in a session table after the owner runs the approved checks. Current Supabase CLI/ Docker availability and local pgTAP status must be recorded separately from Android UI results.

| Area | Status | Evidence / follow-up |
| --- | --- | --- |
| Build, install, launch | NOT TESTED for this tree | Build result is separate; install requires owner approval. |
| PKCE client unit tests | PASS | 68 tests across 17 suites passed, including verifier/challenge, persistence abstraction, callback validation, code exchange request, generic errors, and state cleanup. No live Supabase behavior is covered. |
| Debug build | PASS | `testDebugUnitTest --rerun-tasks` and `assembleDebug` passed; artifact: `app/build/outputs/apk/debug/app-debug.apk`. |
| Install / launch | NOT TESTED | APK was not installed; owner approval and interactive phone checks remain required. |
| Supabase Auth/callback integration | BLOCKED | PKCE implementation is present, but no disposable Supabase project/config is available; do not use production accounts. |
| Supabase RLS/database tests | NOT TESTED | Supabase CLI and Docker are unavailable; use the isolated local procedure before any hosted migration. |
| Profiles/rule precedence | NOT TESTED for this tree | Exercise ordered checklist item 5. |
| Schedules/temporary blocks/focus | NOT TESTED for this tree | Exercise ordered checklist items 6–7. |
| UI-only VPN lifecycle | NOT TESTED for this tree | Do not test internet traffic with discard-only reader. |
| Actual traffic blocking | BLOCKED | Forwarding-capable VPN engine is not implemented. |
| Speed notification | NOT TESTED for this tree | Exercise item 10 independently from VPN. |

## UI and accessibility

1. Open every screen from the main navigation: app list, schedules, temporary blocks, focus modes, profiles, history, speed, usage history, analytics, and account/backup.
2. Check portrait and landscape where supported, small display, large font/display size, and Android TalkBack. Confirm headings and control labels are read in a useful order, switches identify the app, controls are reachable, and no text/buttons are clipped or hidden behind system bars/gesture navigation.
3. Verify theme text/control contrast, 48dp button targets, search behavior, scroll behavior, icon fallbacks, empty states, and validation/error messages.
4. Exercise loading/error/permission states, including unavailable Usage Access settings, declined VPN consent, denied notification permission, signed-out Supabase account, expired/revoked session, no network, and API errors.

## User profiles and rule preservation

1. Create two profiles with distinct blocked and allowed app sets; confirm an empty/invalid name or overlapping package selection is rejected.
2. Edit one profile and verify only its name and package sets change. Leave the screen, kill/relaunch the app, and confirm profiles and the active profile persist.
3. Activate profile A, switch to B, deactivate B, and delete an active profile after checking the confirmation dialog. Confirm only the selected profile record/active ID changes.
4. For one harmless package, test the resolver/UI state with: manual block + profile allow; focus block + profile allow; profile block + scheduled allow; profile allow + scheduled block; profile allow + temporary block. Verify the displayed precedence and that removing/expiring one source leaves the other saved source intact.
5. Check VPN consent and service lifecycle separately. Do not claim app internet was blocked or allowed: the reader currently discards all packets routed into its TUN.

## Supabase automatic backup and restore

1. Configure `supabase.properties` locally with a **disposable test project's** HTTPS URL and publishable/anon key only. Never place a service-role key in the app. Enable Email/password Auth, use email confirmation for signup, and allow-list `appinternetcontrol://auth-callback` for confirmation and recovery redirects. The app starts PKCE flows for signup and recovery, stores the verifier encrypted, and exchanges only a query authorization code; direct password sign-in remains a TLS password grant with no redirect.
2. Run `supabase/tests/user_backup_rls.test.sql` against a disposable local database. Apply `202611020001_user_backups.sql`, then `202611030001_atomic_my_backup_read.sql` locally and verify anon denial and two-user row isolation. Do not apply either migration to a hosted/production database until the local tests pass and the owner explicitly approves.
3. In Account and backup, confirm automatic backup defaults off. Sign in, enable it, and verify initial upload happens only after the explicit opt-in. Choose daily/weekly cadence, confirm last-success advances only after upload/read-back verification, disable it, and verify the periodic job is canceled.
4. Change local settings and wait for a background run. Confirm upload is allowed only when the remote equals the last-sync baseline. Change cloud data from a second device and verify the first device reports conflict and leaves both copies untouched for manual direction choice.
5. Verify each account sees/modifies only its own `app_cloud_settings`, `app_cloud_profiles`, `app_cloud_schedules`, and `app_cloud_focus_modes` rows. Confirm payloads exclude usage history, analytics, passwords, auth tokens, and device permissions.
6. Test offline state, signed-out use, expired/revoked session, RLS denial, missing migration/RPC, and server errors. Confirm local controls work, failed uploads do not advance last-success, and status gives a useful error.
7. Test app restart, reboot, Doze/battery saver, and normal idle; record JobScheduler delay. Confirm sign-out cancels scheduled work and signing into an already opted-in account schedules only that user's backup.
8. Restore a cloud copy and verify confirmation, schema validation before local writes, schedule reconciliation, and exclusion of usage history/Android grants.

## Other feature regression checks

- Schedules: 12-hour AM/PM input, overnight windows, start/end boundaries, missed alarms, reboot/time/time-zone changes, exact-alarm access state, and overlapping schedule/manual/profile rules.
- Temporary blocks: start, cancellation, expiry, process restart, and overlapping sources.
- Focus modes: create/edit/activate/deactivate/delete, state refresh, and profile/schedule/temporary precedence.
- Speed: start/stop and persistent notification cadence; screen-off survival; verify stopping speed does not stop VPN and vice versa.
- Usage: grant Usage Access manually, start/stop collection, capture, coverage warnings, Wi-Fi/mobile totals, and delayed JobScheduler behavior.
- Accounts/cloud: only if project URL and publishable key are configured locally and the user explicitly opts in; verify Auth/backup without exposing credentials. Otherwise mark **NOT CONFIGURED**.

## Reporting rules

Mark each item PASS, FAIL, BLOCKED, or NOT TESTED with device/API, steps, observation, and relevant redacted logs. Distinguish UI/rule persistence from actual traffic enforcement. Repeat failures after a clean app restart and file a focused regression test before claiming a fix. Keep API 29 provisional until real-device VPN and scheduling behavior is validated.

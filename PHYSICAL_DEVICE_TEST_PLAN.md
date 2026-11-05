# Physical-device validation plan

This plan covers the current local-control app. Profiles and account/cloud backup were removed; there are no Auth, sync, or restore checks. The next app launch performs a one-time cleanup of their dedicated local preferences and cancels the retired periodic job. No phone checks for that cleanup have been performed.

## Device matrix

| Tier | Android/API | Device coverage | Network coverage |
| --- | --- | --- | --- |
| Minimum | API 29 / Android 10 | At least one device | Wi-Fi; mobile data if supported |
| Middle | API 34 / Android 14 | At least one device | Wi-Fi and mobile data |
| Current | API 37 / Android 17 when available | At least one device | Wi-Fi and mobile data |
| Current available OEM | Realme RMX3834 / Android 15 / API 35 | Existing reference device; another OEM when practical | Wi-Fi and mobile data |

Record manufacturer/model, Android build, API, ABI, available storage, and VPN/notification/usage permission state. One OEM/API result does not cover another.

## Safety and setup

- Use a debug build reviewed by the owner; do not install without approval.
- Use harmless apps and non-sensitive test data.
- The VPN packet reader is discard-only. **Do not route an app through it for an internet-connectivity test.** Until a forwarding-capable engine is reviewed and implemented, test only consent, service lifecycle, saved effective rules, and status reporting. Actual block/allow traffic tests remain **BLOCKED**.
- Do not test domain filtering; Ad Blocker work is paused and no domain filter is approved.
- Capture `adb devices -l`, Android release/API, manufacturer/model, ABI, and available `/data` storage. Keep logcat scoped to the app and redact device identifiers before sharing.

## Install and launch commands

Run only after approving installation. Use the authorized serial shown by `adb devices -l`:

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

If ADB reports `unauthorized`, unlock the phone and accept its debugging trust dialog. If offline or missing, reconnect and check again; do not bypass authorization. `adb install -r` retains app data, including the retired feature data that the app clears on its first launch after this update.

For each check record **PASS**, **FAIL**, **NOT TESTED**, or **BLOCKED**, plus device/API, steps, observation, and relevant redacted logs. A visible toggle, active service, or saved rule proves only UI/service state, not traffic enforcement.

## Ordered master checklist

1. **Install, startup, navigation, and UI:** inspect launch, app list, search, launcher icons, empty/error states, font scaling, TalkBack labels, and crash logs. Confirm navigation includes only current features; there are no profile or account/backup screens.
2. **Manual app selection:** select and clear harmless apps. Verify names, launcher icons, ordering, persistence, and effective-rule labels. Do not route selected apps through the discard-only VPN to test connectivity.
3. **Schedules:** verify 12-hour AM/PM entry, a 7:00 PM–9:59 AM overnight schedule, exact boundaries, missed alarms, edits, process restart, reboot/time/time-zone changes, and VPN service restart. Record foreground/background delay separately.
4. **Temporary blocks and focus modes:** test creation, activation, cancellation/deactivation, expiry, restart/reboot, overlap with schedules and manual rules, and status refresh. Confirm changing one rule source does not erase another source.
5. **Wi-Fi rules:** test selecting the connected network, saved rule display, Wi-Fi disabled/no network, disconnect/reconnect, and app/process restart. The rule evaluator/UI may be observed, but real app traffic denial/restoration remains **BLOCKED** until forwarding is implemented and reviewed.
6. **VPN consent and service lifecycle only:** test consent grant/decline/revocation and start/stop status. Keep traffic-blocking and unselected-app connectivity checks **BLOCKED** while routed packets are discarded.
7. **Actual network enforcement:** currently **BLOCKED**. After a forwarding-capable VPN engine is separately reviewed, test harmless apps on Wi-Fi and mobile data independently, selected and unselected traffic, IPv4/IPv6, DNS, network switching, service restart, and schedule expiry. Capture independent network evidence.
8. **Speed notification:** start/stop speed monitoring independently from VPN. Verify updates, persistence, notification/channel permissions, screen-off/background behavior, and that stopping one service leaves the other running.
9. **Usage history and analytics:** grant Usage Access manually; test collection start/stop, manual capture, coverage warnings, Wi-Fi/mobile totals, app attribution, analytics, and delayed JobScheduler behavior.

## Current status

A short UI-only smoke check was performed on 2026-11-06 after installing debug version 1.0 (versionCode 1) on the authorized Realme RMX3834 / Android 15 / API 35. It covered screen rendering and navigation only. It did not exercise blocking, rule transitions, alarms, Usage Access collection, or persistence across restarts. This does not replace the ordered master checklist below.

| Area | Status | Evidence / follow-up |
| --- | --- | --- |
| Build | PASS | `testDebugUnitTest assembleDebug` succeeded; 74 unit tests, 0 failures; debug APK at `app\build\outputs\apk\debug\app-debug.apk`. |
| Install / launch | PASS | Authorized USB Realme RMX3834 / Android 15 API 35; installed debug version 1.0 (versionCode 1); MainActivity resumed and no app fatal exception was found in the inspected log window. |
| Main screens/navigation | PARTIAL | App list/icons, Wi-Fi rules/picker, schedules, temporary blocks, focus modes, history, speed, usage, analytics, and About screens opened/rendered. No data-changing control was used. |
| Profile/account screens absent | PASS | Current More features menu contains no profile, account, or backup entry. |
| Legacy local-data cleanup | NOT TESTED on device | First launch clears only retired dedicated preference stores, cancels the old scheduled job, and attempts to delete its Keystore key. |
| Schedules / temporary blocks | PARTIAL | Screens and existing saved schedule rendered. No timer was started and no expiry transition was tested in this smoke pass. |
| Focus mode UI | NOT TESTED after fix | The prior smoke check showed “Deactivate focus mode” with “No focus modes yet.” The current source now derives both from one persisted snapshot; unit tests cover all four states and transitions. Recheck after installing this build. No focus data was changed. |
| Wi-Fi rule UI | PARTIAL | Saved rules and picker rendered. No rule state was changed in this smoke pass. Previous enable/disable state check remains documented above; neither check verifies traffic. |
| VPN UI/service lifecycle | PARTIAL | Home showed the VPN-active/unverified status and notification. No lifecycle transition was tested. |
| Actual traffic blocking/restoration | BLOCKED | VPN reader discards routed packets. |
| Speed notification | PARTIAL | Speed screen showed active monitoring; notification shade showed the VPN and separate speed notifications. Start/stop independence and screen-off behavior were not tested. |
| Usage history/analytics | PARTIAL | Both screens rendered with existing local data. Collection, capture, attribution, and accuracy were not tested. |

## Reporting rules

Mark each item PASS, FAIL, BLOCKED, or NOT TESTED with device/API, steps, observation, and relevant redacted logs. Distinguish UI/rule persistence from actual traffic enforcement. Repeat reproducible failures after restart and add a focused regression test before claiming a fix. Keep API 29 provisional until real-device VPN and scheduling behavior is validated.

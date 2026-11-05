# Screenshot checklist

The public gallery uses two genuine captures from the running debug app on a realme RMX3834 running Android 15/API 35. They were captured on 2026-11-06 from version 1.0 (versionCode 1). They show UI state only; none verifies traffic enforcement.

## Captures in this checkout

- [x] `docs/screenshots/temporary-blocks.png` — temporary-block controls and empty state. No temporary test rule was created.
- [x] `docs/screenshots/live-speed.png` — approximate device-wide speed screen while its existing monitor was running. The displayed rates are a momentary sample, not a performance claim.

An older About/updates capture is retained locally but excluded from the public gallery because it shows version 1.0 and the then-unconfigured update source. The gallery images are project assets. The speed screen contains a transient rate reading, not a performance claim.

## Captures still needed

- [ ] **Main app list/search and app picker:** capture on a clean test device or profile so installed-app names do not expose the owner's personal app inventory. Caption should say that the list includes launchable apps and saved selections do not guarantee blocking.
- [ ] **Wi-Fi rules:** use a non-personal test network and test app label. Do not expose a home SSID or network identifier. Caption should describe the local rule UI and state that network enforcement is experimental.
- [ ] **Schedule editor:** use a disposable test schedule and app label. Show AM/PM controls, not a personal schedule. Caption should avoid promising exact background timing.
- [ ] **VPN limitation information:** capture the info dialog only if the dimmed background does not reveal personal app names, notifications, or other private data. Do not imply filtering is verified.
- [ ] **Usage history/analytics:** only use a dedicated test device with benign test traffic. Never publish personal app names, totals, or capture history.

Do not use mockups, synthetic screens, or edited captures that hide a limitation or make unfinished behavior appear functional. Re-capture on a clean test profile when a screen contains personal information; do not redact by changing app data on the owner's device.

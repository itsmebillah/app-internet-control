# App Internet Control v1.0.1

These notes are prepared for the first public release and should be published only with the verified APK.

This is the first planned public release of App Internet Control, a native Android prototype for exploring local app-selection, Wi-Fi access rules, schedules, temporary blocks, focus modes, and network-usage tools.

## Included

- Searchable list of launchable apps with Android launcher icons.
- Local manual selections, Wi-Fi rules, schedules, temporary blocks, and focus-mode controls.
- Local change history, approximate device-wide speed monitoring, and optional usage history.
- HTTPS update metadata checking that opens the official GitHub Releases page; APKs are never silently installed.

## Important limitation

**Traffic blocking is experimental and unverified.** The current VPN packet reader discards selected-app packets instead of forwarding them. Selected apps may lose connectivity, and saved rules or an active VPN do not prove reliable blocking or restoration. Do not rely on this app for security, parental-control, or safety-critical restrictions.

Schedule timing, Wi-Fi transitions, background operation, usage coverage, and behavior across device makers and Android versions also need broader physical-device testing. This release is not a verified firewall.

## Install

Download the production-signed APK attached to the [official GitHub Release](https://github.com/itsmebillah/app-internet-control/releases/latest). Open it and follow Android’s package installer. Android may ask you to allow your browser or file manager to install unknown apps. Review that prompt before proceeding.

## Compatibility and privacy

- Package: `com.itsmebillah.appinternetcontrol`
- Minimum SDK: Android 10 / API 29 (provisional)
- Target SDK: API 37
- Rules, history, and usage records are stored locally. Usage collection requires the user to enable Usage Access. The app has no account or cloud backup.
- The app’s VPN and notification features use Android’s user-consent and notification flows.
- APK SHA-256: `A531CF464E9A5BADC19517C43AAA29E5E64CB8DBAC1E374E416EDBA998C9E153`

See the repository README for setup, permissions, privacy details, and troubleshooting. This release is not a Google Play release; Play In-App Updates are not implemented.

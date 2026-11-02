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

Features 1–5 include a launchable-app list, locally persisted selections, a consent-gated prototype VPN packet-discard interface, per-app schedules, temporary blocks, named focus groups, and local user profiles. Effective rule precedence is temporary block > active schedule > active profile > active focus group > manual selection; block wins among conflicting schedules. Schedule/timer evaluation covers wall-clock expiry, overnight windows, daylight-saving transitions, schedule conflicts, and next-boundary calculation. The scheduler uses inexact alarms and attempts to reconcile on boot, clock/time-zone changes, app replacement, schedule boundaries, and timer expirations. Nineteen unit tests and the debug build pass. No physical Android device is connected: VPN traffic filtering, alarm/timer timing, and background service startup remain unverified and are labeled accordingly. API 29 remains provisional. Schedule history, network indicators, usage history/analytics, accounts, and sync have not been implemented.

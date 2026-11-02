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

Features 1–7 include a launchable-app list, locally persisted selections, a consent-gated prototype VPN packet-discard interface, schedules, temporary blocks, named focus groups, local user profiles, a local change history, and opt-in live aggregate speed. Effective rule precedence is temporary block > active schedule > active profile > active focus group > manual selection; block wins among conflicting schedules. Schedule/timer evaluation covers wall-clock expiry, overnight windows, daylight-saving transitions, schedule conflicts, and next-boundary calculation. History records manual requests (including overridden requests) and automatic effective transitions with source/timestamp, retains at most 500 events, and labels outcomes pending/inactive, overridden, or active-but-unverified. Live speed estimates device-wide `TrafficStats` deltas at a user-configurable 1/2/5/10-second cadence and uses a user-started foreground notification. Twenty-six unit tests and the debug build pass. No physical Android device is connected: VPN traffic filtering, alarm/timer behavior, notification visibility, speed-counter behavior, battery impact, and background service startup remain unverified. The `specialUse` foreground-service type requires Play policy review. API 29 remains provisional. Usage history/analytics, accounts, and sync have not been implemented.

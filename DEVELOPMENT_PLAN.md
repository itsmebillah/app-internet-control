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

Features 1–8 include a launchable-app list, persisted rules, consent-gated prototype VPN filtering, schedules, timers, focus modes, profiles, change history, live aggregate speed, and opt-in local usage history. Effective rule precedence is temporary block > active schedule > active profile > active focus group > manual selection; block wins among conflicting schedules. Schedule/timer evaluation includes local wall-clock boundaries and daylight-saving behavior. History retains up to 500 local events with source and unverified outcome labels. Speed uses approximate device-wide `TrafficStats` deltas at a user-configurable 1/2/5/10-second cadence with a stoppable user-started foreground notification. Usage history requires user-granted Usage Access; a persisted six-hour job and manual capture query Wi-Fi/mobile per-UID summaries into local SQLite, then aggregate daily, monthly, and since-collection-start totals. Collection gaps, shared UIDs, scope, and history start are disclosed. Thirty unit tests and the debug build pass. No physical Android device is connected: VPN filtering, alarms, notification delivery, speed counter behavior, battery impact, Usage Access, network-stat availability/attribution, and background job timing remain unverified. The speed service's `specialUse` type requires Play review. API 29 remains provisional. Usage analytics, accounts, and sync have not been implemented.

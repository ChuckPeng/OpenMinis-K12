# Changelog

All notable changes to OpenMinis-K12 are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
as defined in `docs/k12/conventions.md` (K12 SemVer is independent of upstream Minis).

## [Unreleased]

## [1.0.0-m0] - 2026-10-29

Milestone **M0 · Thin shell**. Upstream Android baseline: **1.15** (`versionCode` 37).

### Added

- Fork identity: display name `伴学 Minis`, `applicationId` `com.openminis.k12` (namespace remains `com.openminis.app` for upstream merge friendliness).
- K12 package skeleton under `com.openminis.k12` (identity, study clock, screen time, PIN/backup hashing, route/offload/deep-link policies, onboarding wizard, child home / parent / permission screens).
- Child-mode guards (E1, F1, F2): block terminal, settings, file browser, MCP, tools, env vars, bypass activities, disallowed offload tools, scheduled-task creation; speech remains allowed.
- Screen-time tracking for this app only: primary 40m / junior 90m daily, +30m weekend, 25m/40m break reminders, parent PIN +15m extension.
- Onboarding: UUIDv4 `child_id`, salted PIN and backup-password hashes, four-boolean permission self-check.
- Android CI workflow (assemble + K12 acceptance / hook smoke unit tests).
- rclone gomobile stub when `libs/rclone.aar` is absent so CI can assemble without the private AAR.
- Version management: SemVer scheme, tag/release policy, upstream sync, and device-API compatibility in `docs/k12/conventions.md`; root `CHANGELOG.md`; tag release workflow attaching APK (and server binaries when `server/` exists).
- K12 SemVer fields: `versionName` `1.0.0-m0`, `versionCode` `1000001`, upstream baseline recorded in `com.openminis.k12.Version` and `BuildConfig.K12_*`.

### Changed

- `RELEASE_REPO` → `ChuckPeng/OpenMinis-K12`; in-app auto-update check disabled for the fork until self-hosted APK listing lands.
- Documented upstream hooks H-01…H-20 and M0 hook wiring in `docs/k12/upstream-patches.md`.

### Security

- Release keystore material stays out of git; CI fails if a keystore is committed.

[Unreleased]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-m0...HEAD
[1.0.0-m0]: https://github.com/ChuckPeng/OpenMinis-K12/releases/tag/v1.0.0-m0

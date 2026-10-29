# Changelog

All notable changes to OpenMinis-K12 are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
as defined in `docs/k12/conventions.md` (K12 SemVer is independent of upstream Minis).

## [Unreleased]

## [1.0.0-m5a] - 2026-10-29

Milestone **M5a · Server core**. Upstream Android baseline: **1.15** (`versionCode` 37). Product SemVer `1.0.0-m5a` / `versionCode` `1000005` (shared with embedded server ldflags).

### Added

- Go module `server/` (`openminis-k12-server`): SQLite (modernc), dual loopback HTTP ports, `public_base_url`, required `trusted_proxies`, argon2id admin sessions + CSRF (no 2FA), GPL-3.0 (WP-S-1/S-2/S-5).
- WeCom callback `WXBizMsgCrypt` at `/wecom/callback/{callback_key}`; outbox with expiry drop for unlock messages; audit log (WP-S-3/S-4, R3/R5).
- J12 first: documented live-test procedure (`docs/k12/ops/j12-live-test.md`), admin callback harness (`/api/v1/admin/j12/simulate`), and text / 6-digit backup-code approval fallback (J14) (WP-S-0).
- Vue 3 + Vite admin UI under `server/web/` (wizard, WeCom, parents, J12 panel, audit, status), embedded via `go:embed` (WP-S-6).
- systemd unit + install docs (primary); optional Dockerfile / compose example (secondary).
- CI workflow `.github/workflows/server.yml` (`go test`, admin UI build, ldflags `--version`).

### Changed

- Product SemVer → `1.0.0-m5a` / `versionCode` `1000005`.

### Known v1 release blockers

- **BLOCK-CAS-1** (Symja / real CAS for tutoring A4) remains open from M3; not in M5a scope.
- Device API / pairing unlock end-to-end is **M5b** (not started).

## [1.0.0-m3] - 2026-10-29

Milestone **M3 · Mistakes & FSRS**. Upstream Android baseline: **1.15** (`versionCode` 37).

### Added

- Kotlin port of `open-spaced-repetition/java-fsrs` v1.0.0 (MIT headers retained); py-fsrs cross-check vectors with java-fsrs default weights and fuzzing off (WP-M3-1).
- Mistake book: `child_id` isolation, five error causes, `pending_check` excluded from stats, FSRS review queue, correction (= independent redo without full reveal), mastery score/levels (S5), push reasons, three-tier variants without web crawl (B1–B4 / WP-M3-2/3).
- Runtime knowledge-graph install: manifest + sha256 + CC BY-NC-SA 4.0 gate, prereq backtrack; synthetic sample only in assets — no NC / K12-KGraph blobs in git or APK (WP-M3-4, ADR-0011).
- Acceptance tests `K12M3AcceptanceTest`.

### Changed

- Product SemVer → `1.0.0-m3` / `versionCode` `1000004`.

### Known v1 release blockers

- **Bundle a real CAS (Symja or equivalent) for tutoring math checks (A4).** M3 does not ship Symja: `matheclipse-core` 3.0.0 alone is ~7.0 MB jar, and with parser/external/apfloat/hipparchus/choco/kryo (and further transitives) the uncompressed dependency set is roughly **~15–25+ MB** before dex/R8 — out of M3 mistakes scope and a large APK hit. Until a CAS is wired as `CalcEngines.preferred()`, production still falls back to the arithmetic smoke engine from M2. Must land before `1.0.0` GA.

## [1.0.0-m2] - 2026-10-29

Milestone **M2 · Photo tutoring & local reveal**. Upstream Android baseline: **1.15** (`versionCode` 37).

### Added

- Reveal state machine `confirm → guide → hint → step → reveal` (G1) with jailbreak no-ops (G2, A7).
- Stem-confirm gate (A1); hint chain + three approach variants (A2/A3/A5/A6).
- Local reveal auth: parent PIN + optional TOTP offline (G3, G4); M2 request path shows PIN (remote in M5b).
- Leak guard for pre-reveal finals / choices / formulae; fixed block line per T-36.
- Calc check path (A4): model vs tool mismatch → `不确定` / `pending_check`. Arithmetic engine for CI; reflective Symja bridge when the class is present.
- Local study events: session, stem_confirmed, attempt, hint_used, answer_reveal, calc_checked (no question text).
- H-09 one-line `buildSystemPrompt` hook via `TutoringRuntime.promptSuffix()`.
- Acceptance tests `K12M2AcceptanceTest`.

### Changed

- Product SemVer → `1.0.0-m2` / `versionCode` `1000003`.

## [1.0.0-m1] - 2026-10-29

Milestone **M1 · Three-tier skills & course catalog**. Upstream Android baseline: **1.15** (`versionCode` 37).

### Added

- Bundled skill `study-companion-core` (K1): layered hints, no full homework ghostwriting, beyond-syllabus tagging, break reminders, PIN refusal line.
- De-identified `student.md` template (K3) under `assets/k12/templates/`.
- Sample catalogs (WP-M1-3): junior math, junior English vocabulary, primary Chinese / Math / English — synthetic outlines only.
- Official-Minis install instructions (K4): `docs/k12/skills/install-into-official-minis.md`.
- H-18 hook: `SkillRepository` installs the K12 bundle without overriding a newer local skill version; templates materialize into app-private storage if absent.
- Acceptance tests `K12M1AcceptanceTest`.

### Changed

- Product SemVer → `1.0.0-m1` / `versionCode` `1000002`.

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

[Unreleased]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-m3...HEAD
[1.0.0-m3]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-m2...v1.0.0-m3
[1.0.0-m2]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-m1...v1.0.0-m2
[1.0.0-m1]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-m0...v1.0.0-m1
[1.0.0-m0]: https://github.com/ChuckPeng/OpenMinis-K12/releases/tag/v1.0.0-m0

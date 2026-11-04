# Changelog

All notable changes to OpenMinis-K12 are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
as defined in `docs/k12/conventions.md` (K12 SemVer is independent of upstream Minis).

## [Unreleased]

## [1.0.0-rc.6] - 2026-11-04

A tablet on `1.0.0-rc.5` could be approved in the admin UI and still sit on 绑定家长服务器 with last heartbeat 未上线. The pending device id lived only in a Compose `remember`, so a navigation or process restart dropped it and `markPaired` never ran. Upstream Android baseline remains **1.15** (`versionCode` 37). Product SemVer `1.0.0-rc.6` / `versionCode` `1000012`. GA `1.0.0` moves to `versionCode` `1000013`.

### Fixed

- Pending pairing (device id, base URL, child id, certificate fingerprint) is stored as soon as `POST /pair` succeeds, and `profile.pairing` becomes `pending`. Status is polled on process start, on every foreground, about every 10 seconds while the waiting screen is up (`wait` up to 10, server cap 25), and by a 15-minute WorkManager job. `active` saves the endpoint, clears the pending record, syncs immediately, and leaves the waiting screen. `revoked` or HTTP 401/403 clears it and shows the denial. A network failure keeps the pending record.
- The waiting screen shows the host, a refresh button, and cancel. It reads pairing state from `K12Runtime` instead of a one-shot endpoint read.
- Re-pairing with the same device public key reuses the existing row. A different key on an already active child returns 409. A revoked row with the same key returns to `pending_confirm`.
- Heartbeats send cumulative foreground minutes as `daily_used_minutes`. The server stores only the increase, so a repeated report of the same total does not double the day's usage. The admin device list includes `companion_minutes`.
- A parent-configured hotline from the heartbeat is what the second emergency screen shows.
- The parent screen recomposes when a wrong attempt is recorded, so 待复习 and the knowledge-point line appear without leaving the page.
- A sync requested while one is already running waits for that cycle and returns its result. The empty session list shows the New Chat button once the server model is ready, which is the button named by 还没有对话。点下面的按钮开始。 Saving the paired endpoint uses `commit()`, so a process death cannot drop it.
- The chat composer exposes resource id `k12_chat_composer`. A New Chat tap with no visible model entry is logged and does not navigate.
- A parent-delivered model stays available for a new chat. Persisting the provider config rewrites the entry id, and the following cleanup was deleting that entry.
- Confirming the parent PIN opens the parent page. The gate used to pop back to the session list first, that hop locked the parent session, and the parent page was then refused.
- A pair-status poll that is still in flight when the parent confirms cannot write `pending` back over `active`.
- The emergency screen fills the activity window. The parent hotline phrase and number are each their own line, so the second step can show them after the child declines.
- `k12_calc` waits for Symja's first load instead of returning `busy` while warmup still holds the engine. A chat that starts a tutoring session and immediately calls the tool now shows the number.
- With animator duration scale 0, the chat list/detail panes use no slide, so the composer is on screen as soon as New Chat is tapped.

### Added

- Chat can call on-device Symja through the `k12_calc` tool when K12 is installed. A tutoring session starts from the child's message. A wrong attempt is written to the local mistake book.
- Crisis text opens 请马上告诉身边的大人 and queues an alert for the next sync. The message is not sent to the model.
- Emulator e2e (API 30 and 34) runs the pairing restart and a full learning journey against an in-job fake OpenAI server. The feature list is `docs/k12/testing/e2e-matrix.md`. Headless runs do not open a camera; the photo step uses a fixture image. WeCom button callbacks stay on the owner live test.

### Changed

- Product SemVer → `1.0.0-rc.6` / `versionCode` `1000012`.

## [1.0.0-rc.5] - 2026-11-04

Phone testing of `1.0.0-rc.4` still stopped at pairing with “无法生成这台平板的签名密钥。请再试一次。如果仍然失败，请先解除绑定后再绑定。” `DeviceSigningKey.open()` returned null. JVM tests never called AndroidKeyStore. Upstream Android baseline remains **1.15** (`versionCode` 37). Product SemVer `1.0.0-rc.5` / `versionCode` `1000011`. GA `1.0.0` moves to `versionCode` `1000012`.

### Fixed

- Sealing the Ed25519 seed no longer passes a caller-chosen GCM IV into AndroidKeyStore. On a real Android 11 runtime that threw `java.security.InvalidAlgorithmParameterException: Caller-provided IV not permitted`, which `open()` caught and turned into the message above. Encrypt now lets the keystore generate the IV and stores that 12-byte IV with the ciphertext. Decrypt still supplies the stored IV. `net.i2p.crypto:eddsa` 0.3.0 signed and verified on that same runtime (64-byte signature), so the wire format is unchanged. On an Android 14 emulator the hardware Ed25519 key failed its self-test (`InvalidKeyException: Keystore operation failed`, incompatible digest) and the software seed was used; that fallback now seals successfully.
- Key-generation failures log the exception class and message. Long token-like strings in the message are redacted. Key bytes are not logged. `Throwable` is caught, so an `Error` such as `NoClassDefFoundError` is recorded instead of escaping the `catch (Exception)`.

### Added

- GitHub Actions workflow `.github/workflows/android-e2e.yml` (`pull_request` and `workflow_dispatch`) runs an API 30 and API 34 x86_64 emulator with KVM. It builds the debug and androidTest APKs, starts the Go server with `public_base_url` at `http://10.0.2.2:PORT`, creates an admin and a pair code, then runs `K12DeviceE2ETest`: AndroidKeyStore key, pair to `active`, admin tablet-model push, tablet applies it, and the onboarding title “第一次设置” is visible in dark mode. Logcat and the screenshot are uploaded as artifacts on every run. Plain http is accepted only when a debug test sets the flag; release builds still require https.

### Changed

- Product SemVer → `1.0.0-rc.5` / `versionCode` `1000011`.

## [1.0.0-rc.4] - 2026-11-04

Phone testing of `1.0.0-rc.3` stopped at pairing: the tablet showed “无法生成这台平板的设备密钥” and never called the server. Upstream Android baseline remains **1.15** (`versionCode` 37). Product SemVer `1.0.0-rc.4` / `versionCode` `1000010`. GA `1.0.0` moves to `versionCode` `1000011`.

### Fixed

- Device signing is Ed25519 on every API this app supports (`minSdk` 26). AndroidKeyStore’s `Ed25519` generator is not available on most phones, and the rc.3 P-256 fallback cannot be sent: the server accepts only a raw 32-byte Ed25519 public key. API 33+ still tries a hardware key (`EC` + `ed25519`) and keeps it only when a self-test signature verifies. Otherwise the app generates the 32-byte seed in software (`net.i2p.crypto:eddsa` 0.3.0) and stores it as AES-256-GCM ciphertext under a non-exportable AndroidKeyStore AES key. An existing P-256 alias is deleted and replaced before pairing. Unpair or reset deletes the signing alias, the AES alias, and the ciphertext. Key bytes are not logged. If generation still fails, logcat records the exception class name and the parent form says to retry, then unpair and pair again.

### Changed

- Product SemVer → `1.0.0-rc.4` / `versionCode` `1000010`.

## [1.0.0-rc.3] - 2026-11-04

Phone testing of `1.0.0-rc.2` found three blockers. This candidate fixes them and drops the dedicated child home page. Upstream Android baseline remains **1.15** (`versionCode` 37). Product SemVer `1.0.0-rc.3` / `versionCode` `1000009` (STAGE 9). That uses the last single-digit STAGE on the `1.0.0` line, so GA `1.0.0` will use `versionCode` `1000010`.

### Fixed

- System dark mode no longer paints K12 text black on a black background. `MinisTheme` and `MinisAppearance` provide `LocalContentColor` as `colorScheme.onBackground`. K12 roots use a `Surface` with that background and content color, covering onboarding, parent settings, permissions, pairing, waiting, and the screen-time lock.

### Added

- Tablet pairing in the PIN-gated parent area, and as a skippable last onboarding step (“绑定家长服务器”). The parent enters the server URL (https only, no userinfo) and the 6-digit pair code, pastes the admin payload, or scans the QR (`k12://pair` or the JSON payload). The tablet posts its raw Ed25519 public key to `POST /api/v1/device/pair`, polls `GET /api/v1/device/pair/status`, and calls `K12Runtime.markPaired` only when the status is `active`. Pending confirmation does not schedule sync. The parent area shows the server host and can unpair. Invalid or expired codes, network failures, and TLS failures use clear Chinese messages.
- The admin pair-code panel shows a QR image (`配对二维码`) of `qr_payload` beside the 6-digit code. The payload includes `public_base_url` and `base_url`.

### Changed

- The K12 home screen (title, stage, nickname, remaining minutes, “开始学习”, “家长”) is no longer the landing page. After onboarding, an unpaired tablet shows a pairing-needed state. A paired tablet with no model yet shows a waiting state. Once a server-managed model is applied, or a parent has configured a local provider, the app opens the stock session list so the child can start or continue a chat. Parent PIN settings, pairing, permissions, limits, and model status sit behind the existing gear icon. Remaining screen-time minutes are a chip in the top bar. The screen-time lock and the break banner still apply.
- Full tablet model config (`GET /api/v1/device/tablet-model`) is fetched once right after pairing or initialization, then at most once per `ModelConfigSchedule.INTERVAL_MS` (24 hours). A newer `tablet_model_version` on the heartbeat fetches immediately and does not wait out the interval. The 15-minute WorkManager job and the foreground one-off still send the heartbeat, apply `config_json`, and upload usage; those runs do not refetch the full model config when the version is unchanged and the interval has not elapsed. A failed pull does not count as success, so the next sync retries. A successful pull of an unchanged version waits the interval. Unpair clears the timestamp.
- Product SemVer → `1.0.0-rc.3` / `versionCode` `1000009`.

### Security

- The pairing form stays behind the parent PIN. The UI shows the server host, not the full URL. The wrapped code secret is not logged.

## [1.0.0-rc.2] - 2026-11-03

Parent configures the tablet chat model once in the admin UI. Paired tablets store it in the Android Keystore and apply it through the existing provider settings. Model traffic is not proxied. Upstream Android baseline remains **1.15** (`versionCode` 37). Product SemVer `1.0.0-rc.2` / `versionCode` `1000008` (STAGE 8). ADR-0015 amends ADR-0006.

### Added

- Family-level tablet model config: provider type, Base URL, model name, optional vision model, and a write-only API key encrypted with the master key (`GET`/`PUT`/`DELETE /api/v1/admin/tablet-model`). No per-child override. No default vendor. Base URL checks are syntax only. No spend budget.
- Heartbeat field `tablet_model_version` only. Paired devices fetch the key from `GET /api/v1/device/tablet-model`. Unpaired, pending, and revoked devices get 401 or 403. Each delivery is audited with device id, version, and time.
- Admin page（平板讲题模型）shows the masked key state, version, and last delivery per device.
- Android applies the config through `ProviderRepository` (`addInstance` / `updateInstance` / `saveApiKey`). While it is active the provider screens are read-only. Clearing the server config restores the previous local provider selection. Unpair or reset wipes the delivered key.
- Paired tablets schedule sync with WorkManager: a 15-minute periodic job (the contract has no interval; 15 minutes is the platform minimum), plus a one-off run when the app comes to the foreground and when pairing becomes active. Each run sends the heartbeat, applies `config_json`, fetches the tablet model when its version changes, and uploads queued events. Unique work names use KEEP. Unpair or reset cancels the jobs.

### Security

- The API key is never returned by the admin API, never placed on the heartbeat, and never written to logs or audit targets.

## [1.0.0-rc.1] - 2026-11-03

Release candidate after M5b. Clears the last v1 code blocker before `1.0.0` GA. Upstream Android baseline remains **1.15** (`versionCode` 37). Product SemVer `1.0.0-rc.1` / `versionCode` `1000007` (STAGE 7).

### Added

- On-device Symja CAS for tutoring A4 (`org.matheclipse:matheclipse-core:3.2.0`, with `matheclipse-parser` and `matheclipse-external`). `CalcEngines.preferred()` is `SymjaCalcEngine`. Numeric checks, expression equivalence, simplification, factoring, and univariate solution sets (order-insensitive) run on Symja. The arithmetic smoke engine remains for tests that pass it explicitly.
- Guards: length 512, nesting 32, an allow-list of arithmetic heads (so `While`, `Set`, `Import`, and compound expressions are not evaluated), non-finite results, a 2s per-check budget, and at most four discarded engines. A timed-out engine is dropped so the next check can run. Loading happens on a daemon thread; the Android main thread gets `main-thread` and the tutoring path records tool-unavailable instead of blocking.
- Acceptance tests `K12CasA4AcceptanceTest` and `K12CasMainThreadTest` (wired into `.github/workflows/android.yml`).

### Changed

- Product SemVer → `1.0.0-rc.1` / `versionCode` `1000007`.
- **BLOCK-CAS-1 resolved.** Maven modules `matheclipse-core`, `matheclipse-parser`, and `matheclipse-external` 3.2.0 are **LGPL-3.0** (Symja relicensed those modules in 3.1.1). LGPL-3.0 can be combined into this GPL-3.0 app; the library is unmodified and its source is the Symja tag `v3.2.0`. Class files are Java 11 bytecode, built with the repo's Java 17 toolchain. `minSdk` stays 26. Guava is forced to `33.6.0-android`. Multidex is explicit. R8 keeps `org.matheclipse.**` and `edu.jas.**` and may shrink the rest. The APK growth and the LGPL dependency are accepted.
- Equation solutions are real by default. `x^2+1==0` is `{}` (no real solution), not `{-I, I}`. `CasField.COMPLEX` keeps non-real roots. `CasPolicy` honors that only for `StudyStage.SENIOR` when the setting is on; the setting defaults off, and primary or junior stay real.
- Equivalence is over the reals. A domain hole or radicand that only one side has is `CalcEquivalence.Restricted` (for example `x!=1`, `x!=0`, `x!=-2`, `x>=0`) and is not a full match. Identical domains still match.
- On-device or emulator confirmation of CAS startup is deferred. This version is checked with JVM unit tests and Robolectric.

### APK size

Measured locally against main `156b68c` (arm64-v8a, debug keystore, `assembleDebug` / `assembleRelease`):

- Debug APK: 55,104,438 → 72,650,370 bytes (+17,545,932, +16.7 MiB). Dex files 20 → 23. Method ids 296,911 → 539,344 (R8 off).
- Release APK (R8): 25,795,147 → 33,550,633 bytes (+7,755,486, +7.4 MiB). Dex files 1 → 2. Method ids 46,006 → 115,444. `minSdk` 26 loads the extra dex without a multidex support library.

Uncompressed Maven jars for the Symja tree are on the order of 40 MB before dex and R8. R8 keeps `org.matheclipse.**` and `edu.jas.**`, which is most of the release growth. `assets/org/hipparchus/random/new-joe-kuo-6.21201` is about 692 KiB compressed and is not used by A4. Plotting code that references `java.awt` is not on the tutoring path; those references are `-dontwarn`'d.

## [1.0.0-m5b] - 2026-10-30

Milestone **M5b · Tablet Integration & Device API**. Upstream Android baseline: **1.15** (`versionCode` 37). Product SemVer `1.0.0-m5b` / `versionCode` `1000006` (shared between Android and Go server).

### Added

- Device pairing protocol: QR / manual 10-minute one-time code, device public key registration, wrapped `codeSecret` (sealed box), WeCom confirmation card, certificate fingerprint pinning (J1, J9, J11, WP-B-1).
- Remote reveal unlock: device signed unlock request, WeCom template card approval (question / 15-minute / deny), server Ed25519 grant signing, device grant verification, long-polling with fast <= 10s unlock, idempotent callbacks (J2–J8, J10, WP-B-2).
- Offline reveal fallback: offline 6-digit backup code derived via HMAC-SHA256 from `codeSecret`, 5 wrong attempts locks 10 min, local PIN & RFC 6238 TOTP fallback when server unreachable within 5s (J6, J13, G5, WP-B-2).
- Three-level alerts & content safety: 提示 / 关注 / 紧急 with 30-minute deduplication, encrypted snippet storage with 30-day retention, WeCom alert delivery without snippets, immediate emergency alert, F5-F7 emergency guidance without hardcoded hotline numbers (E5, H1–H5, WP-B-3).
- Study events & daily aggregate: idempotent event upload (max 500 per batch, gzip), local day boundary at 04:00 (Asia/Shanghai), metrics computation (active seconds, attempts, hints, reveals, mistakes, flags), Go template daily briefing with HMAC signed link and noindex/no-referrer report page, device child report without parent advice (WP-B-4, S8, S11, S16, S17, E4).
- Heartbeat & parental controls: server time calibration, config version sync with §19.4 keys (no alert thresholds), turn limit (60), image limit (20), companion limit (15 min) (WP-B-5, WP-B-7).
- Encrypted device backups: `K12B` format, AES-256-GCM with PBKDF2/Argon2id, server retention (7 daily + 4 weekly), zero plaintext access on server (WP-B-6, §19.6).
- Diagnostics & APK manifest: sanitized crash/log upload, app update check endpoint (WP-B-8, §19.7, §19.13).
- Vue 3 Admin UI enhancements: device management, alert audit with snippet viewing, and report generation/link management.

### Changed

- Product SemVer → `1.0.0-m5b` / `versionCode` `1000006`.

### Known v1 release blockers

- **BLOCK-CAS-1** (Symja / real CAS for tutoring A4) remains open from M3.

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

[Unreleased]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-rc.6...HEAD
[1.0.0-rc.6]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-rc.5...v1.0.0-rc.6
[1.0.0-rc.5]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-rc.4...v1.0.0-rc.5
[1.0.0-rc.4]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-rc.3...v1.0.0-rc.4
[1.0.0-rc.3]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-rc.2...v1.0.0-rc.3
[1.0.0-rc.2]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-rc.1...v1.0.0-rc.2
[1.0.0-rc.1]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-m5b...v1.0.0-rc.1
[1.0.0-m3]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-m2...v1.0.0-m3
[1.0.0-m2]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-m1...v1.0.0-m2
[1.0.0-m1]: https://github.com/ChuckPeng/OpenMinis-K12/compare/v1.0.0-m0...v1.0.0-m1
[1.0.0-m0]: https://github.com/ChuckPeng/OpenMinis-K12/releases/tag/v1.0.0-m0

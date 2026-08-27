# Enhancement Report

> **Generated:** 2026-08-27 (second pass — supersedes the first)
> **Project:** PowerOff Remote
> **Stack:** Kotlin 2.2.20 · Jetpack Compose (Material 3) · AGP 8.13.1 / compileSdk 36 · mwiede/jsch 2.28.7 · Android Keystore

---

## Executive Summary

Since the first pass the project has moved on materially: all five quick wins shipped, the
repository is **public**, and CI is green — 22 JVM tests per push including nine that drive a real
sshd on the runner, guarded so a silent skip fails the build. Release builds are signed. The app is
~3,400 lines of Kotlin with 53 tests written.

Two things now define the shortest path forward, and the first is unglamorous. **The repository has
no LICENSE.** A public repo without one is "all rights reserved": nobody may legally use, fork or
redistribute it, F-Droid will not accept it, and any contribution sits in a grey area. It is a
ten-minute fix that unblocks every downstream option.

The second is the standing blocker, and there is now a way to remove it permanently. The 31
instrumented tests covering the Keystore vault have **never executed** — they need a physical
device, and device access has been intermittent. GitHub-hosted `ubuntu-latest` runners support KVM,
so [android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner) can run
`connectedDebugAndroidTest` in CI. Better still, an API-level matrix would exercise the four
version-conditional branches in `CryptoManager` and `AppLock` that no single phone can reach. That
converts "written but unverified" into "verified on every push, across four Android versions".

Everything else — the widget, surviving process death, the encrypted backup, the Keystore-backed SSH
key — remains as assessed in the first pass and is carried forward below.

---

## Current State Analysis

### What This Project Does

Stores server credentials in an encrypted on-device vault and drives those machines' power state:
shutdown and reboot over SSH (handling `sudo -n` or feeding a password to `sudo -S` on stdin), and
power-on via a Wake-on-LAN magic packet — broadcast from the phone on the LAN, or relayed over SSH
by another saved server when away. Every action lands in an encrypted, capped audit log.

### Technology Stack

| Category | Technology |
|---|---|
| Language | Kotlin 2.2.20 |
| UI | Jetpack Compose, Material 3 1.4.0, Navigation Compose 2.9.5 |
| Build | AGP 8.13.1, Gradle 8.14.3, JDK 17 target |
| SDK | compileSdk 36, targetSdk 36, minSdk 26 |
| SSH | com.github.mwiede:jsch 2.28.7 (+ net.i2p.crypto:eddsa 0.3.0) |
| Crypto | Android Keystore, AES-256-GCM, StrongBox where available |
| Storage | Encrypted JSON blobs on internal storage; SharedPreferences for non-secrets |
| Auth | AndroidX Biometric 1.1.0 |
| CI | GitHub Actions — tests (incl. live sshd), lint, both APKs |
| Tests | 22 JVM (0 skipped in CI) + 31 instrumented (never executed) |

### Current Strengths

- **CI that cannot lie.** `scripts/test-summary.py --require-all` fails the build if any test
  skipped, so the nine SSH integration tests provably run rather than quietly opting out. Verified
  in both directions.
- **Credentials are properly protected** — non-exportable Keystore key, randomised IVs, GCM tag
  verified, backup and device-transfer disabled, `FLAG_SECURE` on by default, and the platform's
  reported protection level now shown in Settings (red when it says `software`).
- **Host key pinning fails closed** — a changed key aborts during KEX, before userauth.
- **Every dependency is FOSS.** Verified against the release runtime classpath: zero GMS, zero
  Firebase. AndroidX and kotlinx are Apache-2.0, jsch is BSD-style, eddsa is public-domain/BSD. This
  makes F-Droid inclusion viable the moment a licence exists.
- **targetSdk 36 meets the 31 August 2026 Play deadline** with nothing left to do.
- **Signed release builds** that degrade to unsigned when credentials are absent, so a fresh clone
  and CI both still work.

### Improvement Opportunities

- **No LICENSE** — blocks legal reuse, contribution and F-Droid. Highest-leverage single fix.
- **31 instrumented tests have never run.** The riskiest code in the app is covered on paper only.
- **No widget or Quick Settings tile** — the core action still takes five interactions.
- **Actions do not survive process death** — an app-scoped `CoroutineScope` loses in-flight work if
  the process is reclaimed mid-connect.
- **No Dependabot, no CodeQL** — both free on public repos, and CodeQL has Android-specific queries.
- **No SECURITY.md** — for a public credential-handling app, there is no stated way to report a flaw.
- **No release automation** — the signed APK is built locally and shared by hand.
- **No CHANGELOG, CONTRIBUTING, or screenshots** in a now-public repo.
- **No backup** — losing the phone still means re-entering every credential.
- Carried forward: no grouping/search, no jump host for the SSH connection itself, no PTY fallback
  for `requiretty` sudoers.

---

## Enhancement Recommendations

### Quick Wins (High Impact, Low Effort)

#### 1. Add a LICENSE — do this first
**Why:** Public with no licence means all rights reserved. Nobody can legally use or fork it,
F-Droid will reject it, and contributions have no clear terms. Everything else in this report that
involves other people is gated on it.
**How:** `gh repo license` or drop in a file. **Apache-2.0** suits this project better than MIT: it
grants an explicit patent licence and requires modifications to be flagged, which matters for
security-sensitive code others may fork.
**Impact:** Ten minutes. Unblocks distribution, contribution and F-Droid.

#### 2. Run the instrumented tests on an emulator in CI
**Why:** 31 tests, 459 lines, never executed. They cover the Keystore vault — the part that must be
right. Device access has proven unreliable twice.
**How:** GitHub-hosted runners support KVM once a udev rule opens it up:

```yaml
  instrumented:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        api-level: [26, 29, 31, 36]
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v6
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
            | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm
      - uses: ReactiveCircus/android-emulator-runner@v2
        with:
          api-level: ${{ matrix.api-level }}
          arch: x86_64
          script: ./gradlew :app:connectedDebugAndroidTest
```

**Choose those API levels deliberately.** They are not arbitrary — each one reaches a branch no
single phone can:

| API | Branch it exercises |
|---|---|
| 26 | `minSdk` floor; `java.util.Base64` availability in `SshClient` |
| 29 | `AppLock`'s `SDK_INT !in 28..29` exclusion, where BIOMETRIC_STRONG + DEVICE_CREDENTIAL is unsupported |
| 31 | `securityLevel()` crossing from the deprecated `isInsideSecureHardware` to `KeyInfo.securityLevel` |
| 36 | Current target |

**Impact:** Turns the single largest verification gap into a per-push guarantee. Note emulators have
no StrongBox, so `reportsWhereTheKeyLives` will log `TEE` or `software` — the test asserts set
membership rather than a specific value precisely so this passes honestly.

#### 3. Turn on Dependabot and CodeQL
**Why:** Both are free on public repos. CodeQL natively supports Kotlin and ships Android-specific
queries (TrustManager validation, debuggable attributes, intent redirection). Dependabot catches
known-vulnerable dependency versions — relevant given jsch's Terrapin history.
**How:** `.github/dependabot.yml` with the `gradle` and `github-actions` ecosystems weekly, plus the
default CodeQL workflow with `language: java-kotlin`.
**Impact:** ~15 minutes for continuous supply-chain and static analysis.

#### 4. Add SECURITY.md
**Why:** This app handles SSH credentials and is now public. There is currently no stated way to
report a vulnerability privately.
**How:** A short policy pointing at GitHub private vulnerability reporting (enable it in repo
settings), stating what is in scope and an expected response time.

#### 5. README badges, screenshots and a CHANGELOG
**Why:** The README is genuinely good on the technical detail but a visitor cannot see what the app
looks like or whether it is maintained.
**How:** CI status badge, licence badge, minSdk badge; three screenshots (list, editor, activity log)
captured once the emulator CI exists — `adb shell screencap` needs `FLAG_SECURE` off, so capture
with the setting toggled. Keep a `CHANGELOG.md` in Keep-a-Changelog format from the first tag.

---

### Feature Enhancements

#### 1. Home-screen widget and Quick Settings tile
**Description:** A Glance widget listing servers with a live status dot and a power button each; a
`TileService` bound to one designated server.
**User Value:** Still the single feature separating this from the apps people keep. Shutdown should
be one tap from the home screen, not five interactions.
**Technical Approach:** [Glance](https://developer.android.com/jetpack/androidx/releases/glance)
`GlanceAppWidget` + `GlanceAppWidgetReceiver`; tile icons must be solid-white 24×24dp
`VectorDrawable`. Power must route through a small transparent confirm activity — the confirmation
is a safety feature and the widget must not bypass it. Google's guidance is that a surface which
only *displays* state should be a widget, so let the widget show and the tile act.
**Prerequisite:** ship the WorkManager change below first; a widget that fires an action which can
silently vanish is worse than no widget.

#### 2. Automated signed releases
**Description:** Push a tag, get a GitHub Release with the signed APK attached and the changelog
section as the body.
**User Value:** Right now the only way to get the app onto a phone is to build it. A release page
makes it installable by anyone, and is a prerequisite for F-Droid's update tracking.
**Technical Approach:** A `release.yml` triggered on `v*` tags, with the keystore stored as a
base64 repository secret and decoded at build time, plus `RELEASE_STORE_PASSWORD` and friends as
secrets — the build already reads them from the environment, so no Gradle changes are needed.

#### 3. Encrypted backup and restore
**Description:** Export the vault encrypted under a user-chosen passphrase; import on a new device.
**User Value:** A wiped phone currently means re-entering everything. A passphrase-encrypted export
is strictly better than the current all-or-nothing: useless without the passphrase and it never
touches the Keystore key.
**Technical Approach:** Argon2id (or PBKDF2-HMAC-SHA256 at ≥600k iterations to avoid a native
dependency — note F-Droid requires FOSS dependencies either way), AES-256-GCM, written via
`ACTION_CREATE_DOCUMENT`, with a versioned header.

#### 4. Groups, ordering and search
Carried forward. Matters at a dozen servers, not at three.

#### 5. Jump host for the SSH connection
Carried forward. JSch does this natively: connect to the bastion, `setPortForwardingL(0, target, 22)`,
then open a second session to the forwarded port. The `wakeGatewayId` field already establishes the
pattern of one saved server referencing another — reuse it as `jumpHostId`.

---

### Technical Improvements

#### Performance
- **Bound `refreshAll` concurrency.** It launches one coroutine per server, each doing a blocking
  4s TCP connect on `Dispatchers.IO`. A `Semaphore(6)` keeps a dozen servers from saturating the
  dispatcher — and it now runs automatically whenever the list appears, so it fires more often.
- **Baseline profile** is already generated. Revisit with a Macrobenchmark run once the widget lands.

#### Security
- **Show the fingerprint on first connect.** Real TOFU asks. `SshOutcome.hostKeyFingerprint` is
  already returned on every connect; present it with "Trust this host?" so it can be compared
  against `ssh-keygen -lf` on the server.
- **Consider `setUnlockedDeviceRequired(true)`** on the Keystore key — blocks decryption while the
  screen is locked. Test against the widget path before committing to it.
- **Zero out secrets after use.** Passwords live in `String` and linger in the heap until GC.
  `CharArray`/`ByteArray` with explicit clearing is stricter; worth it for `sudoPassword` at least.
- **PTY fallback for `requiretty` sudoers**, as a per-server toggle calling `channel.setPty(true)`.
- **Enable private vulnerability reporting** now the repo is public.

#### Code Quality
- **`ServerEditorScreen.kt` (463 lines)** should split per section — each becomes independently
  previewable.
- **`PowerController.kt` (281 lines)** mixes command construction, execution and audit. Extracting a
  pure `CommandBuilder` pays off; `gatewayWakeCommand` already proves how testable that shape is.
- **No Compose UI tests.** The confirm-dialog logic is safety-critical and already produced one real
  bug. `createAndroidComposeRule` fits neatly into the emulator CI job above.
- **`ActionRunner` swallows exceptions** into a generic result — log them, or a user report is
  undebuggable.

---

### Architecture Evolution

The three-layer split remains right for this size. One change is worth making before the widget:

```
  ui / widget / tile
        │
        └──▶ ActionRunner ──▶ PowerController ──▶ SshClient / WolSender
                   │
                   └── replace app-scoped CoroutineScope with
                       WorkManager expedited OneTimeWorkRequest
                         ├── survives process death
                         ├── retry with backoff on flaky mobile data
                         └── one dispatch path shared by app, widget and tile
```

Android 16 caveat: jobs started from a foreground service now count against runtime quotas, and
long-running workers using foreground services can exhaust the app's job quota. A power action takes
seconds, so **expedited work is the right fit** — designed for user-initiated tasks finishing within
minutes. Avoid a long-running worker here.

---

### Innovation Opportunities

#### An SSH key that never leaves the secure element
Still the standout opportunity, and concretely implementable. Android Keystore can generate a
non-exportable **EC P-256** key; SSH's `ecdsa-sha2-nistp256` is exactly ECDSA over P-256 with
SHA-256. The app could generate a key whose private half never exists outside the secure element,
print the public half for `authorized_keys`, and sign the userauth challenge inside the TEE.

JSch supports it via a custom `Identity` — verified present in 2.28.7:

```
public interface com.jcraft.jsch.Identity {
    byte[] getPublicKeyBlob()
    byte[] getSignature(byte[] data)
    String getAlgName()          // "ecdsa-sha2-nistp256"
}
// registered with: jsch.addIdentity(identity, null)
```

`getSignature` delegates to `Signature.getInstance("SHA256withECDSA")` on the Keystore `PrivateKey`.
**Gotcha:** Java emits a DER-encoded ECDSA signature while SSH expects `mpint r || mpint s` — a short
conversion is required. Optionally `setUserAuthenticationRequired(true)` so each shutdown demands a
fingerprint at the crypto layer, not just the UI.

No app in this category does this — and the category leader's own F-Droid description warns that its
credentials are stored unencrypted.

#### Reproducible builds and F-Droid
Now viable: the repo is public and every dependency is FOSS. F-Droid does not *require* reproducible
builds, but treats them as best practice and encourages them for new apps — worth adopting from the
start, since Android will not accept an update signed with a different key.

#### Automation
Scheduled shutdown via WorkManager; Wi-Fi-SSID or geofence triggers to offer a wake on arriving home
(opt-in, since it needs location); an exported permission-guarded intent for Tasker.

#### Emerging Tech
Material 3 Expressive reaches stable in Material Compose 1.5.0 later in 2026, and Google is now
Compose-first with Views in maintenance mode. Adopting it needs AGP 9 / compileSdk 37 first — see
the pin comment in `gradle/libs.versions.toml`. A Wear OS tile is a natural fit for a one-action app.

---

## Implementation Roadmap

### Immediate (This Week)
- [ ] **Add a LICENSE (Apache-2.0)** — blocks everything downstream
- [ ] Emulator CI job running the 31 instrumented tests across API 26/29/31/36
- [ ] Dependabot + CodeQL
- [ ] SECURITY.md and enable private vulnerability reporting
- [ ] Back up `app/poweroff-release.keystore` off this machine

### Short-term (This Month)
- [ ] README badges and screenshots; start a CHANGELOG
- [ ] Tag-triggered signed release workflow
- [ ] Move power actions to WorkManager expedited work
- [ ] "Trust this host?" fingerprint prompt on first connect
- [ ] Bound `refreshAll` concurrency with a semaphore
- [ ] Split `ServerEditorScreen`; extract a pure `CommandBuilder`
- [ ] Compose UI tests for the confirm-dialog paths

### Medium-term (Next Quarter)
- [ ] Glance widget with live status; Quick Settings tile
- [ ] Encrypted passphrase-protected backup and restore
- [ ] Groups, ordering and search
- [ ] Jump-host support; PTY fallback for `requiretty`
- [ ] Submit to F-Droid

### Long-term (6+ Months)
- [ ] Keystore-generated SSH key with in-TEE signing via a custom JSch `Identity`
- [ ] Reproducible builds
- [ ] Scheduled and trigger-based power actions
- [ ] AGP 9 / compileSdk 37 and Material 3 Expressive
- [ ] Wear OS companion tile

### Done since the first pass
- [x] GitHub Actions CI, with a guard that fails on silently skipped tests
- [x] Key protection level surfaced in Settings
- [x] Status refreshed when the list appears
- [x] Wake-then-verify polling with a diagnosable timeout message
- [x] Configurable biometric grace period
- [x] Signed release builds
- [x] Repository made public after a full-history secret audit

---

## Research Sources

| Topic | Source | Link |
|---|---|---|
| Android emulator on CI with KVM | ReactiveCircus/android-emulator-runner | https://github.com/ReactiveCircus/android-emulator-runner |
| Play target API deadline (API 36, 31 Aug 2026) | Play Console Help | https://support.google.com/googleplay/android-developer/answer/11926878 |
| Terrapin / CVE-2023-48795 in mwiede:jsch | Snyk | https://security.snyk.io/vuln/SNYK-JAVA-COMGITHUBMWIEDE-6130900 |
| CodeQL Kotlin support | GitHub codeql discussions | https://github.com/github/codeql/discussions/11460 |
| Java/Kotlin CodeQL queries incl. Android | GitHub Docs | https://docs.github.com/en/code-security/code-scanning/managing-your-code-scanning-configuration/java-kotlin-built-in-queries |
| F-Droid inclusion requirements | F-Droid Docs | https://f-droid.org/en/docs/Inclusion_How-To/ |
| F-Droid reproducible builds | F-Droid Docs | https://f-droid.org/docs/Reproducible_Builds/ |
| Android 16 behaviour changes | Android Developers | https://developer.android.com/about/versions/16/behavior-changes-all |
| Foreground service changes and quotas | Android Developers | https://developer.android.com/develop/background-work/services/fgs/changes |
| Glance app widgets | Android Developers | https://developer.android.com/jetpack/androidx/releases/glance |
| Quick Settings tile guidance | Android Developers | https://developer.android.com/develop/ui/views/quicksettings-tiles |
| Material is Compose-first / M3 Expressive | Material Design | https://m3.material.io/blog/material-is-compose-first |
| Duorem — stores SSH credentials unencrypted (its own warning) | F-Droid | https://f-droid.org/en/packages/com.vadimfrolov.duorem/ |

---

## Recommended Next Steps

1. **Start with:** the LICENSE. It is ten minutes and it is the gate on contribution, redistribution
   and F-Droid.
2. **Then:** the emulator CI job. It permanently retires the "written but never executed" problem
   and, with the four-level matrix, tests version branches no single phone can reach.
3. **Plan for:** WorkManager expedited execution *before* the widget — the widget is the headline
   feature, but shipping it on top of an action that can silently vanish would be the wrong order.

---

*Second-pass analysis based on the current codebase, its git history, CI results, dependency graph, and 2026 Android platform and category research.*

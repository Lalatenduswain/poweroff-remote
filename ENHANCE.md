# Enhancement Report

> **Generated:** 2026-08-27
> **Project:** PowerOff Remote
> **Stack:** Kotlin 2.2.20 · Jetpack Compose (Material 3) · AGP 8.13.1 / compileSdk 36 · mwiede/jsch 2.28.7 · Android Keystore

---

## Executive Summary

PowerOff Remote is a single-purpose Android app: store server credentials on-device, shut those
machines down over SSH, and wake them again with a Wake-on-LAN magic packet. It is ~3,300 lines
of Kotlin across 23 files, with 22 JVM tests (9 of them driving a real sshd) and 31 instrumented
tests covering the Keystore vault. The security posture is already ahead of the category: the
closest open-source equivalent, [Duorem](https://f-droid.org/en/packages/com.vadimfrolov.duorem/),
stores SSH credentials in **unencrypted** app preferences, while this app keeps them in an
AES-256-GCM blob keyed from the Keystore, pins host keys on first use, and aborts the handshake
before sending a credential if the key ever changes.

The gap is not security — it is **reach and reliability**. Everything the app can do is locked
behind opening it, unlocking it with a fingerprint, finding the server and tapping through a
confirmation. The competing apps that people actually use ([WolOn](https://wolon.app/) and
WOL-Manager) win on exactly this: home-screen widgets, status dots you can see without opening
anything, and grouping. Meanwhile a power action here runs in an application-scoped coroutine, so
if Android reclaims the process mid-connect the action vanishes with no record.

The three things worth doing, in order: **(1)** a Glance widget and Quick Settings tile so a
shutdown is one tap, **(2)** move power actions onto WorkManager expedited work so they survive
process death, and **(3)** an SSH key generated *inside* StrongBox whose private half never exists
in app memory — a genuine first for this category and a natural extension of what is already built.

---

## Current State Analysis

### What This Project Does

Stores a list of servers (host, port, user, password or private key, sudo password, MAC address,
custom shutdown/reboot commands) in an encrypted on-device vault. Power off and reboot run the
configured command over SSH, handling `sudo -n` or feeding a stored password to `sudo -S` on stdin.
Power on sends a Wake-on-LAN magic packet either broadcast from the phone (same LAN only) or
relayed over SSH by another saved server (works from anywhere). Every action is written to an
encrypted, capped audit log.

### Technology Stack

| Category | Technology |
|---|---|
| Language | Kotlin 2.2.20 |
| UI | Jetpack Compose, Material 3, Navigation Compose 2.9.5 |
| Build | AGP 8.13.1, Gradle 8.14.3, JDK 17 target |
| SDK | compileSdk 36, targetSdk 36, minSdk 26 |
| SSH | com.github.mwiede:jsch 2.28.7 (+ net.i2p.crypto:eddsa) |
| Crypto | Android Keystore, AES-256-GCM, StrongBox where available |
| Storage | Encrypted JSON blobs on internal storage; SharedPreferences for non-secrets |
| Auth | AndroidX Biometric 1.1.0 |
| Serialization | kotlinx.serialization 1.9.0 |
| Tests | JUnit4, androidx.test 1.6.x; 22 JVM + 31 instrumented |

### Current Strengths

- **targetSdk 36 already meets the deadline.** Google Play requires new apps and updates to
  target API 36 from **31 August 2026** — four days away. No work needed.
- **Credentials are properly protected.** Non-exportable Keystore key, randomised IVs, GCM tag
  verified, cloud backup and device-transfer disabled, `FLAG_SECURE` on by default. Verified by
  18 instrumented tests including tamper rejection on ciphertext, tag and IV.
- **Host key pinning that actually fails closed.** A changed key aborts during KEX, before
  userauth — proven by a test that connects with deliberately wrong credentials and still gets a
  host-key error rather than an auth error.
- **The SSH edge cases are covered.** A shutdown that returns no exit status counts as success; the
  same thing with a sudo refusal on stderr does not. Both directions are pinned by tests against a
  real sshd.
- **jsch is current and past Terrapin.** 2.28.7 is the latest release (2026-08-20);
  [CVE-2023-48795](https://security.snyk.io/vuln/SNYK-JAVA-COMGITHUBMWIEDE-6130900) was fixed in
  0.2.15.
- Clean separation: `net` (transport) → `domain` (orchestration + audit) → `ui`, with no framework
  types leaking downward. Crypto and file names are injectable, so tests never touch live data.

### Improvement Opportunities

- **No widget, no Quick Settings tile.** The core action takes five interactions.
- **Actions do not survive process death.** `ActionRunner` uses an app-scoped `CoroutineScope`;
  a backgrounded app being reclaimed mid-connect loses the action silently.
- **Status is always `UNKNOWN` on launch** until manually refreshed, and a wake gives no feedback
  until you refresh by hand.
- **The biometric lock re-locks on every `ON_STOP`** with no grace period, so a two-second app
  switch forces re-authentication.
- **No backup.** Deliberate, and documented — but losing the phone means re-entering everything.
- **The release build is unsigned**, so there is no installable artefact.
- **First-use host key trust is silent.** OpenSSH shows you the fingerprint and asks; this pins
  without a word, which is the weaker half of TOFU.
- **`securityLevel()` is implemented but not shown** anywhere in the UI.
- **No CI.** The repo is on GitHub with no workflow running the suite.
- **No jump-host support** for the SSH connection itself (only the WoL relay).
- **No grouping, search or ordering** in the server list.

---

## Enhancement Recommendations

### Quick Wins (High Impact, Low Effort)

#### 1. GitHub Actions CI
**Why:** 53 tests exist and nothing runs them on push. Regressions in the SSH exit-status logic are
exactly the kind that pass code review and break a real shutdown.
**How:** One workflow: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Add the
SSH integration properties pointed at a `linuxserver/openssh-server` service container so the nine
skipped tests actually run in CI.
**Impact:** ~10 minutes of setup; every push verified.

#### 2. Show where the key lives
**Why:** `CryptoManager.securityLevel()` already returns `StrongBox` / `TEE` / `software`. The whole
value proposition is "your credentials are safe" — prove it in the UI.
**How:** One line in the Settings "Stored data" card:
```kotlin
Text("Encryption key protected by: ${CryptoManager.default.securityLevel()}")
```
**Impact:** ~5 lines. Turns an invisible security claim into a visible one.

#### 3. Refresh status on open, and after a wake
**Why:** Every server reads "Unknown" until you tap refresh, which makes the list look broken.
**How:** `LaunchedEffect(Unit) { runner.refreshAll(servers) }` on the list screen. After a successful
wake, poll `Reachability.check` every 5s for 90s and update the dot when it comes up.
**Impact:** The list becomes glanceable and the wake button finally reports whether it worked.

#### 4. Biometric grace period
**Why:** Re-locking on every `ON_STOP` punishes checking a MAC address in another app.
**How:** Record `SystemClock.elapsedRealtime()` on stop; only re-lock when the gap exceeds a
configurable threshold (Immediately / 30s / 1 min / 5 min), defaulting to 30s.
**Impact:** Removes the most common daily friction without weakening the lock meaningfully.

#### 5. Release signing config
**Why:** `assembleRelease` produces an unsigned APK today — you cannot install the 2.2MB build.
**How:** Keystore file plus `signingConfigs` reading the password from `local.properties` or env
vars, so the keystore itself never has to be committed.
**Impact:** A shippable artefact, and the on-device instrumented run stops needing the 20MB debug APK.

---

### Feature Enhancements

#### 1. Home-screen widget and Quick Settings tile
**Description:** A Glance widget listing servers with a live status dot and a power button each; a
Quick Settings tile bound to one designated server for one-tap shutdown.
**User Value:** This is the single feature separating this app from the ones people actually keep.
WolOn's widget with green/red status dots is its headline feature. A shutdown should be one tap
from the home screen, not five.
**Technical Approach:** [Glance](https://developer.android.com/jetpack/androidx/releases/glance)
`GlanceAppWidget` + `GlanceAppWidgetReceiver`; a `TileService` for the tile. Tapping power must open
a small transparent confirm activity rather than firing directly — the confirmation is a safety
feature and the widget must not bypass it. Note Google's guidance that a tile which only *displays*
information should be a widget instead, so the tile should act, and the widget should show state.
Tile icons must be solid white 24×24dp `VectorDrawable`.
**Caveat:** the widget cannot decrypt the vault while the app lock is engaged if you later bind the
Keystore key to user auth — keep the current unbound key, which was chosen for exactly this reason.

#### 2. Encrypted backup and restore
**Description:** Export the vault to a single file encrypted with a user-chosen passphrase; import
it on a new device.
**User Value:** Today, a lost or wiped phone means re-entering every credential by hand. The README
frames "no export" as a security decision, and it is a defensible one — but a *passphrase-encrypted*
export is strictly better than the current all-or-nothing, because it is useless without the
passphrase and never touches the Keystore key.
**Technical Approach:** Derive with Argon2id (or PBKDF2-HMAC-SHA256 at ≥600k iterations if avoiding
a native dependency), encrypt with AES-256-GCM, write via `ACTION_CREATE_DOCUMENT`. Version the
format. Warn clearly, and require the passphrase to be typed twice.

#### 3. Wake-then-verify
**Description:** After sending a magic packet, show a progress state that polls until the machine
answers on its SSH port, then flips to "Online" — with a timeout and a plain-language failure
("no answer after 90s — is Wake-on-LAN enabled in the BIOS?").
**User Value:** Wake-on-LAN fails silently and for boring reasons. Closing the loop turns the most
frustrating part of the app into the most reassuring.

#### 4. Groups and ordering
**Description:** Tag servers (`home lab`, `office`), collapse by group, reorder by drag, and wake or
shut down a whole group.
**User Value:** WOL-Manager's group feature is explicitly called out as its differentiator. At 3
servers this does not matter; at 12 it does.

#### 5. Jump host for the SSH connection
**Description:** Reach a server through a bastion, the way `ProxyJump` does.
**Technical Approach:** JSch supports this directly — open a session to the bastion, then
`session.setPortForwardingL(0, target, 22)` and connect a second session to the forwarded local
port. The `wakeGatewayId` field already establishes the pattern of one saved server referencing
another; reuse it as `jumpHostId`.

---

### Technical Improvements

#### Performance

- **Parallelise `refreshAll`.** It launches one coroutine per server already, but each does a
  blocking 4s TCP connect on `Dispatchers.IO`. With a dozen servers this saturates the dispatcher.
  Bound it with a `Semaphore(6)`.
- **Trim the debug APK.** 20MB debug vs 2.2MB release is almost entirely Compose tooling. Harmless,
  but it makes every transfer to a test device slow — build a signed release variant for on-device work.
- **Baseline profile is already generated** by `compileReleaseArtProfile`. Consider a custom profile
  driven by a Macrobenchmark run once the widget exists.

#### Security

- **Show the fingerprint on first connect.** Real TOFU asks. Present the `SHA256:...` string with
  "Trust this host?" the first time, so it can be compared against `ssh-keygen -lf` on the server.
  The plumbing already exists — `SshOutcome.hostKeyFingerprint` is returned on every connect.
- **Consider `setUnlockedDeviceRequired(true)`** on the Keystore key. It blocks decryption while the
  screen is locked without breaking widget use once unlocked. Test carefully against the widget path.
- **Zero out secrets after use.** `Server` holds passwords in `String`, which stays in the heap until
  GC. `CharArray`/`ByteArray` with explicit clearing is the stricter pattern, though kotlinx
  .serialization makes this awkward — worth it for `sudoPassword` at least.
- **Add a PTY fallback for sudo.** Hosts with `requiretty` in sudoers will fail with the current
  no-PTY exec. A per-server "allocate a terminal" toggle calling `channel.setPty(true)` covers them;
  document that the password must not be echoed.
- **Rate-limit the unlock screen** after repeated biometric failures, and consider wiping after N
  failures as an opt-in.

#### Code Quality

- **`ServerEditorScreen.kt` is 463 lines** and holds the whole form. Split per section
  (`ConnectionSection`, `AuthSection`, `WakeSection`) — each is independently previewable.
- **`PowerController.kt` at 281 lines** mixes command construction, execution and audit. Extract
  `CommandBuilder` (pure, easily unit-tested — `gatewayWakeCommand` already proves the value).
- **No Compose UI tests.** `createAndroidComposeRule` plus the existing instrumented setup would
  cover the confirm-dialog logic, which is safety-critical and where a real bug already appeared.
- **`ActionRunner` swallows exceptions into a generic result.** Log them; a silent
  `e.javaClass.simpleName` is hard to debug from a user report.

---

### Architecture Evolution

The current three-layer split is right for this size and should not be over-engineered. Two changes
are worth making as the app grows:

**Move action execution off the app-scoped coroutine.**

```
  ui  ──▶  ActionRunner  ──▶  PowerController  ──▶  SshClient / WolSender
                │
                └── replace with ──▶ WorkManager expedited OneTimeWorkRequest
                                       ├── survives process death
                                       ├── retry with backoff on flaky mobile data
                                       └── result surfaces via WorkInfo, not a SharedFlow
```

Note the Android 16 caveat: jobs started from a foreground service now count against runtime
quotas, and long-running workers using foreground services can exhaust the app's job quota. A power
action is short (seconds), so **expedited work is the right fit** — it is designed for user-initiated
tasks completing within a few minutes. Avoid a long-running worker here.

**Introduce a `ServerActionSource` abstraction** once the widget and tile exist, so app UI, widget
and tile all dispatch through one path rather than three call sites duplicating confirmation and
audit logic.

---

### Innovation Opportunities

#### An SSH key that never leaves the secure element

This is the standout opportunity, and it is concretely implementable today.

Right now a private key is pasted into the app and stored encrypted — good, but the key material
still exists in memory whenever a connection is made. Android Keystore can generate an **EC P-256**
key that is non-exportable and, on capable devices, StrongBox-backed. SSH's `ecdsa-sha2-nistp256`
algorithm is exactly ECDSA over P-256 with SHA-256. So the app can generate a key whose private
half has never existed outside the secure element, print the public half for
`~/.ssh/authorized_keys`, and sign the userauth challenge inside the TEE.

JSch supports this through a custom `Identity` — verified present in 2.28.7:

```kotlin
public interface com.jcraft.jsch.Identity {
    byte[] getPublicKeyBlob()
    byte[] getSignature(byte[] data)
    String getAlgName()          // "ecdsa-sha2-nistp256"
    ...
}
// registered with: jsch.addIdentity(identity, null)
```

`getSignature` delegates to `Signature.getInstance("SHA256withECDSA")` initialised with the Keystore
`PrivateKey`. **One real gotcha:** Java returns a DER-encoded ECDSA signature, while SSH expects
`mpint r || mpint s`; a short conversion is required. Optionally set
`setUserAuthenticationRequired(true)` on the key so each shutdown demands a fingerprint *at the
crypto layer*, not just at the UI.

No app in this category does this. It would make "your credentials never leave the device" literally
true rather than approximately true.

#### Automation

- **Scheduled shutdown** — "power off the lab at 23:00 on weeknights" via WorkManager, with a
  notification offering a snooze.
- **Geofence or Wi-Fi-SSID triggers** — offer to wake the lab when you join home Wi-Fi. Needs
  location permission, so make it strictly opt-in and explain why.
- **Tasker / intent integration** — expose an exported (permission-guarded) intent so power actions
  can be scripted.

#### Emerging Tech

- **Material 3 Expressive** will be promoted to stable in Material Compose 1.5.0 later in 2026, and
  Google is now [Compose-first](https://m3.material.io/blog/material-is-compose-first) with Views in
  maintenance mode. Worth adopting once it lands — but it will require moving to AGP 9 / compileSdk
  37 first (see the pin comment in `gradle/libs.versions.toml`).
- **Wear OS companion** — a watch tile for "shut down the lab" is a natural fit for a
  one-action app, and reuses the same Glance-style surface.

---

## Implementation Roadmap

### Immediate (This Week)
- [ ] Run the instrumented suite on a physical device — 31 tests are written and have never executed
- [ ] Raise the GitHub Actions budget, or the CI workflow cannot start (see note below)
- [x] Add GitHub Actions CI (unit tests + lint + assembleDebug)  *(done 2026-08-27)*
- [x] Surface `securityLevel()` in Settings  *(done 2026-08-27)*
- [x] Refresh status on list open  *(done 2026-08-27)*
- [x] Add release signing config  *(done 2026-08-27)*

### Short-term (This Month)
- [x] Biometric grace period, configurable in Settings  *(done 2026-08-27)*
- [x] Wake-then-verify polling with a clear timeout message  *(done 2026-08-27)*
- [ ] "Trust this host?" fingerprint prompt on first connect
- [ ] Move power actions to WorkManager expedited work
- [ ] Split `ServerEditorScreen` into per-section composables
- [ ] Compose UI tests for the confirm-dialog paths

### Medium-term (Next Quarter)
- [ ] Glance home-screen widget with live status
- [ ] Quick Settings tile bound to one server
- [ ] Encrypted passphrase-protected backup and restore
- [ ] Groups, ordering and search in the server list
- [ ] Jump-host support for the SSH connection
- [ ] PTY fallback for `requiretty` sudoers

### Long-term (6+ Months)
- [ ] Keystore-generated SSH key with in-TEE signing via a custom JSch `Identity`
- [ ] Scheduled and trigger-based power actions
- [ ] Migrate to AGP 9 / compileSdk 37 and adopt Material 3 Expressive
- [ ] Wear OS companion tile
- [ ] Consider publishing to F-Droid — the category's leading open-source option stores credentials in plaintext

---

## Research Sources

| Topic | Source | Link |
|---|---|---|
| Play target API deadline (API 36, 31 Aug 2026) | Play Console Help | https://support.google.com/googleplay/android-developer/answer/11926878 |
| Target SDK requirements | Android Developers | https://developer.android.com/google/play/requirements/target-sdk |
| Terrapin / CVE-2023-48795 in mwiede:jsch | Snyk | https://security.snyk.io/vuln/SNYK-JAVA-COMGITHUBMWIEDE-6130900 |
| Android 16 behaviour changes | Android Developers | https://developer.android.com/about/versions/16/behavior-changes-all |
| Foreground service changes and quotas | Android Developers | https://developer.android.com/develop/background-work/services/fgs/changes |
| Glance app widgets | Android Developers | https://developer.android.com/jetpack/androidx/releases/glance |
| Quick Settings tile guidance | Android Developers | https://developer.android.com/develop/ui/views/quicksettings-tiles |
| Material is Compose-first / M3 Expressive | Material Design | https://m3.material.io/blog/material-is-compose-first |
| Five years of Jetpack Compose | Android Developers Blog | https://android-developers.googleblog.com/2026/07/five-years-of-jetpack-compose.html |
| Duorem (stores SSH credentials unencrypted) | F-Droid | https://f-droid.org/en/packages/com.vadimfrolov.duorem/ |
| WolOn (widgets, status dots, SSH commands) | wolon.app | https://wolon.app/ |

---

## Recommended Next Steps

1. **Start with:** running the 31 instrumented tests that are already written but have never
   executed. Everything else is speculation until the Keystore vault is verified on real hardware.
2. **Then:** the Glance widget and Quick Settings tile. It is the difference between an app you
   open and an app you use.
3. **Plan for:** WorkManager expedited execution before the widget ships — a widget that fires an
   action which can silently vanish is worse than no widget.

---

> **Note on CI (2026-08-27):** the workflow is committed and its runner logic was verified in a
> clean `ubuntu:24.04` container, but the first run was refused by GitHub with *"The job was not
> started because an Actions budget is preventing further use."* Private repositories consume
> Actions minutes; either raise the spending limit in GitHub billing settings or make the repo
> public, which gets unlimited free Actions.

*Analysis based on the current codebase, its git history, and 2026 Android platform and category research.*

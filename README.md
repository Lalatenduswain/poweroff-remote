# PowerOff Remote

An Android app that stores your server credentials on-device and turns those machines
off and on again.

- **Power off / reboot** — runs a shutdown command over SSH.
- **Power on** — sends a Wake-on-LAN magic packet, either straight from the phone or
  relayed over SSH by another server you have already saved.
- **Credentials** — passwords, SSH private keys and sudo passwords are encrypted with an
  AES-256-GCM key that is generated inside the Android Keystore and cannot be exported.

## Why power on works differently

A powered-off machine has no SSH daemon, so it cannot be reached the same way it was shut
down. The only thing that wakes it is a Wake-on-LAN magic packet on the local network, and
a magic packet is a broadcast frame — it does not route across the internet or through a
VPN. The app therefore offers two wake paths:

| Wake method | How it works | Use it when |
|---|---|---|
| **From phone** | The phone broadcasts the packet itself | The phone is on the same Wi-Fi/LAN as the server |
| **Via gateway** | Another saved server is asked over SSH to emit the packet | You are away from home, on mobile data, or on Tailscale/a VPN |

The gateway relay tries `wakeonlan`, then a `python3` one-liner, then `etherwake`, so it
usually works on a stock server with nothing extra installed.

## Server-side setup

**Let the SSH user shut the machine down.** Connecting as `root` needs nothing. Otherwise
either store the sudo password in the app, or — better — grant a passwordless exception
for just the power commands:

```
# /etc/sudoers.d/poweroff-remote
lalatendu ALL=(ALL) NOPASSWD: /sbin/shutdown, /sbin/poweroff, /sbin/reboot
```

Then leave the sudo password blank in the app; it will use `sudo -n`.

**Enable Wake-on-LAN.** Turn on "Wake on LAN"/"Power on by PCIe" in the BIOS/UEFI, then on
the running machine:

```
sudo ethtool -s eth0 wol g          # one-off
sudo ethtool eth0 | grep Wake-on    # should report "Wake-on: g"
```

Make it stick across reboots with a systemd unit or a NetworkManager
`ethtool.wake-on-lan` setting, otherwise it resets on every boot. Get the MAC with
`ip link show eth0`.

## Security model

- The vault (`servers.vault`, `activity.vault`) is a single AES-256-GCM blob. The key lives
  in the Android Keystore, StrongBox-backed where the device supports it, and never leaves
  it. Root access to the device does not hand over the key.
- **Host keys are pinned on first use.** The fingerprint the server presents on the first
  successful connect is stored. If it ever changes the handshake is aborted *before* any
  credential is sent, and the app says so. Rebuilt a box on purpose? Open it and tap
  "Forget the pinned key".
- App lock (fingerprint / face / device PIN) and `FLAG_SECURE` are on by default, so the
  app is excluded from screenshots and the recents thumbnail.
- Cloud backup and device-transfer are disabled for the app's data. Nothing is ever sent
  anywhere except to the servers you configured.
- **There is no backup or export.** That is deliberate: an exportable vault is the weakest
  link. If you wipe the app or lose the phone, re-enter the credentials.
- Values that get interpolated into a remote shell command (MAC, broadcast address, port)
  are filtered down to `[A-Za-z0-9.:-]` before use.

## Building

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:assembleRelease      # minified, needs signing before install
```

### Integration tests against a real sshd

`SshIntegrationTest` drives the actual SSH client — auth, exit status, stdin delivery, host-key
pinning and the "no exit status means the box went down" rule — against a live server. It is
skipped unless you point it at one, so a plain test run stays offline:

```bash
FP=$(ssh-keyscan localhost 2>/dev/null | ssh-keygen -lf - | awk '{print $2}' | paste -sd,)
./gradlew :app:testDebugUnitTest \
  -Ppoweroff.itHost=127.0.0.1 \
  -Ppoweroff.itUser="$USER" \
  -Ppoweroff.itKey="$HOME/.ssh/id_rsa" \
  -Ppoweroff.itFingerprint="$FP"
```

Nothing in the suite shuts the target down. The power-off path is covered by running a command
that dies by signal, which is what sshd reports when a machine disappears mid-command. Point it
at `127.0.0.1` and it needs no remote host at all.

### Instrumented tests on a device

`CryptoManagerTest` and `VaultStorageTest` run on real hardware, because the Android Keystore
cannot be faked on a JVM. Together they cover key generation, GCM round-trips, tamper rejection,
the on-disk vault, and the "wrong key means start empty rather than crash" path.

```bash
./gradlew :app:connectedDebugAndroidTest
```

With the phone wired to another machine, install both APKs there and drive the runner directly:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w \
  com.lalatendu.poweroffremote.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Both classes generate a throwaway Keystore alias and their own vault file names per run, and
delete them afterwards, so running the suite on your own phone cannot touch a live vault. The
first assertion in each class checks exactly that. `CryptoManagerTest` also logs which security
level the device gave the key (StrongBox, TEE or software) under the tag `CryptoManagerTest`.

Requires JDK 17+, Android SDK with API 36. `minSdk` is 26 (Android 8.0).

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Stack

Kotlin · Jetpack Compose (Material 3) · Navigation Compose · kotlinx.serialization ·
[mwiede/jsch](https://github.com/mwiede/jsch) for SSH · AndroidX Biometric.
No backend, no analytics, no network calls other than to your own servers.

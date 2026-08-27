# Security Policy

PowerOff Remote holds SSH passwords, private keys and sudo passwords on the device. Please treat
anything that could expose them as a security issue rather than an ordinary bug.

## Reporting a vulnerability

**Do not open a public issue.** Use GitHub's private vulnerability reporting on the
[Security tab](https://github.com/Lalatenduswain/poweroff-remote/security/advisories/new).

Please include the affected version or commit, what an attacker would gain, and the steps to
reproduce it. You should get a first response within about a week.

## In scope

- Anything that lets stored credentials be read outside the app — from another app, from a backup,
  from logs, from the clipboard, or from an unencrypted file on disk.
- Weaknesses in the vault: key handling, the AES-GCM construction, or the on-disk format.
- Sending a credential to the wrong host: failures in host key pinning, or a path where a
  credential is transmitted before the host key is verified.
- Command injection through any value that reaches a remote shell.
- Bypassing the app lock or `FLAG_SECURE`.
- A power action being sent to a server other than the one the user selected.

## Out of scope

- Anything requiring a rooted or already-compromised device, or physical access to an unlocked
  phone. The threat model assumes the device itself is trusted.
- The absence of a backup or export feature. That is a deliberate decision, documented in the
  README: the Keystore key is non-exportable by design.
- Weak credentials or an insecure sshd on a server the user chose to add.
- Reports from automated scanners with no demonstrated impact.

## What the app already does

- Credentials live in a single AES-256-GCM blob whose key is generated in the Android Keystore,
  StrongBox-backed where the hardware allows, and is never exportable.
- Host keys are pinned on first use; a changed key aborts the handshake before any credential is
  sent.
- Cloud backup and device-transfer are disabled for the app's data.
- Values interpolated into a remote shell command are filtered to `[A-Za-z0-9.:-]`.

These properties are covered by tests — see `CryptoManagerTest`, `VaultStorageTest` and
`SshIntegrationTest`. A patch that weakens any of them should fail CI.

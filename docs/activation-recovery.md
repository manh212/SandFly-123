# Activation and recovery

Meridian accepts existing SFLIC1 registrations while the original installation
key and saved registration are available. New registrations use SFREQ2/SFLIC2.
The new request hashes the Android ID together with the application ID and a
protocol domain separator. It does not depend on a generated installation key.

On the same device, Android user and APK signing identity, Android 8 and later
normally preserve that ID through app data clearing and reinstallation. A
factory reset, another Android user, or another APK signing identity can change
it. The ID is not a cryptographic proof of hardware identity and can be spoofed
in a compromised environment. This design does not provide remote attestation
or make a modified APK impossible to use.

## Initial owner setup

1. Install `meridian-admin.apk` from the rolling dev release. It installs as
   `com.sandfly.offlinelicenser.v2`, alongside the previous issuer. Do not remove
   or clear the previous issuer.
2. Set a device PIN or password and select **Tạo khóa cấp phép**. The new tool
   requires a hardware-backed key. It prefers StrongBox, falls back to TEE,
   and rejects software-only signing keys.
3. Create a fine-grained GitHub token. Select only `manh212/SandFly-123` and
   grant **Contents: Read and write**. Choose an expiration date and replace
   the token in the tool when it expires. This repository permission covers
   repository contents, not just one directory; keep the token private.
4. Enter the token directly in the tool and select **Lưu token và kết nối**.
   The tool registers only its public key in
   `talkback/distribution-profile.json`. It refuses to overwrite a different
   nonempty public key. No issuer private key or token is uploaded.
5. Wait for the resulting Meridian build and dev publication to succeed, then
   install that newer `meridian.apk`. The build pins the new public key in
   `BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY`. An APK built before this setup
   cannot accept the new key just by downloading a catalog record.

The new issuer APK uses the existing stable APK signing identity in Actions
secrets and a monotonic version code. Future issuer updates install in place.
The signing identity of the APK and the private key used to sign licenses are
different keys with different purposes.

## Issue and migrate registrations

The user opens activation settings and copies SFREQ2. The administrator enters
it, optionally adds a recipient name for local records, and selects
**Cấp phép và lưu GitHub**. Each issuance requires system authentication.
Android 11 and later bind authentication to the individual signing operation.
Android 8–10 require a fresh credential confirmation in the interface and use
a ten-second Keystore authentication validity window.

The signed registration is stored locally before any upload. Its public record
is `docs/catalog/<device-hash>.json`, with only a format number and the signed
SFLIC2 value. Recipient names are absent from both the public file and signed
payload. The tool uses the GitHub contents API, existing blob SHA values,
bounded conflict retries, and a durable local queue. Already uploaded records
are not rewritten. Changes to one device do not replace another device's file.

Network failures keep the entry pending. The tool retries when opened, and
Android JobScheduler retries queued uploads with network connectivity and
backoff. Android may defer background work. Token permission or expiration
errors require the administrator to replace or fix the token.

An existing SFLIC1 user can update Meridian, copy SFREQ2, and have the new tool
issue the replacement before clearing data. Existing SFLIC1 codes are not
automatically converted and cannot recover a lost installation key.

## Restore on the phone

When activation settings open or the service starts while unlocked, Meridian
looks up the device's public catalog record over HTTPS. It uses no GitHub token.
It checks bounded response size, protocol format, the pinned public key,
signature, device hash and official APK signing identity before saving the
registration. It never trusts a public key supplied by the downloaded record.

A valid saved registration works offline. A fresh install needs connectivity
at least once to recover, or a valid SFLIC2 pasted manually. The existing
60-second unregistered service countdown remains; successful recovery cancels
it. Locked boot does not start recovery or the countdown. No missing or invalid
record is treated as successful activation.

## Administrator data and limits

The new issuer stores its GitHub token, recipient names and queue as AES-256-GCM
ciphertext, with a hardware-backed Keystore key and independent random IVs.
Field names are authenticated as additional data. Backup and cleartext traffic
are disabled. The window blocks screenshots; the token field is a password
field excluded from autofill. API credentials are sent only to a fixed GitHub
HTTPS host, without automatic redirects or credential logging.

Do not uninstall or clear the new administrator app. Its private signing key
is non-exportable and cannot be recovered from GitHub. If it is lost, a new
issuer identity and explicit trust migration are necessary. A compromised
administrator phone or GitHub account remains a serious risk. Obfuscation and
hardware storage do not prevent every attack on a compromised OS.

R8 covers both the Android licensing packages and shared protocol/publishing
code. CI checks actual DEX output and does not publish R8 mapping files. Shared
protocol tests verify device binding, tamper rejection and issuer pinning.
Publisher tests cover bootstrap refusal, conflict retries, idempotency, offline
failure and isolation of device records. Hardware-backed signing, credential
prompts, background job timing and screen-reader focus require phone testing.

# Activation and recovery

Current Meridian clients use SFREQ2 device requests and accept only online
SFLIC3 policies. Earlier clients accepted SFLIC1 and SFLIC2 offline codes;
these no longer grant access after updating. The request hashes the Android ID together with the application ID and a
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
3. Select **Đăng nhập GitHub**. Sign in as `manh212` in the temporary browser
   window, confirm the preconfigured private GitHub App, and choose **Only
   select repositories**, then **pkg-7f3c9a2e**, on GitHub's installation page.
   GitHub requires this initial login and permission confirmation; the tool
   does not read passwords or approve access invisibly. No token is pasted.
4. After confirmation, the browser returns automatically to the tool. It
   registers only its public license verification key in
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
SFLIC3 policy. Recipient names are absent from both the public file and signed
payload. The tool uses the GitHub contents API, existing blob SHA values,
bounded conflict retries, and a durable local queue. Already uploaded records
are not rewritten. Changes to one device do not replace another device's file.

Network failures keep the entry pending. The tool retries when opened, and
Android JobScheduler retries queued uploads with network connectivity and
backoff. Android may defer background work. Token permission or expiration
errors require the administrator to reconnect or fix the GitHub App installation.
Installation tokens are regenerated automatically for each synchronization;
ordinary token expiry does not require another browser login. Existing encrypted
PAT connections continue until browser setup completes, then the old local token
is removed. Revoking a previously created PAT on GitHub is a separate owner action.

An existing SFLIC1 or SFLIC2 user can update Meridian, copy SFREQ2, and have
the owner publish an SFLIC3 policy for the device. Existing offline codes are
not automatically converted and never authorize a new Meridian build.

## Restore on the phone

When activation settings open or the service starts while unlocked, Meridian
looks up the device's public catalog record over HTTPS. It uses no GitHub token.
It checks bounded response size, protocol format, the pinned public key,
signature, device hash and official APK signing identity before saving the
registration. It never trusts a public key supplied by the downloaded record.

A signed SFLIC3 policy verified from the catalog remains valid within the
established seven-day offline lease. Fresh installations and legacy offline
license holders must connect and retrieve a valid SFLIC3 policy. The existing
60-second unregistered service countdown remains; successful verification
cancels it. Locked boot does not start recovery or the countdown. No missing
or invalid record is treated as successful activation.

## Administrator data and limits

The new issuer stores its GitHub App credential, recipient names and queue as AES-256-GCM
ciphertext, with a hardware-backed Keystore key and independent random IVs.
Field names are authenticated as additional data. Backup and cleartext traffic
are disabled. The window blocks screenshots. API credentials are sent only to a fixed GitHub
HTTPS host, without automatic redirects or credential logging.

The serverless browser setup uses GitHub's App Manifest API. A random 256-bit
route and state protect a listener bound only to 127.0.0.1 for at most thirty
minutes. HTTP is used only for this local browser handoff; remote API traffic
uses HTTPS. The listener checks Host, bounds request sizes, disables caching
and framing, and verifies the app owner and actual installation using GitHub's
API before accepting completion. Neither a callback installation ID nor an
external custom-scheme intent is trusted as proof of authorization. The browser
completion state expires, is single-use, and is stored encrypted. No credential
appears in the completion URI. A canceled setup can be resumed through the
login button; if the OS kills the process before configuration is saved, the
owner may need to remove an unused GitHub App registration in GitHub settings.

The GitHub App RSA private key is generated by GitHub and stored encrypted on
the phone; unlike the hardware license signing key, it is available in process
memory during authentication. Unneeded client and webhook secrets are discarded.
Installation tokens are requested with only this repository and Contents: write;
this permission covers repository contents, not just the catalog directory.
The app refuses an installation configured for all repositories. While the
temporary Custom Tab is open, it checks the actual installation every five
seconds, within the same thirty-minute deadline. A verified installation can
complete natively even if GitHub's final setup-page redirect is blocked. The
browser is opened for a result, allowing the administration activity to finish
that browser activity after verification, without a background activity launch.
Returning manually to the app resumes verification of encrypted pending setup.
Polling stops when setup completes, is explicitly canceled or the deadline expires.
Polling and callback paths share the same serialized verification and reject
stale or canceled sessions. No callback installation ID grants access.
Remove the GitHub App installation to revoke synchronization. Browser behavior
and automatic return require testing on the owner's phone.

The creation callback is also part of the authentication session. A temporary
data-sync foreground service keeps its loopback receiver alive while the owner
uses the browser. Pending state, port, route and deadline are saved encrypted
before opening the browser and restored on a sticky service restart. A browser
result callback alone does not end the session. The service notification and
the tool provide **Hủy đăng nhập GitHub**; completion and expiry stop the service.
No notification runtime permission is requested. OS force-stop still interrupts
the flow and requires reopening the tool.

For a blocked creation callback, **Tiếp tục từ liên kết đã sao chép** consumes
the copied GitHub **this setup page** URL locally. It checks the loopback host,
path, port, unexpired encrypted state and unique query parameters before sending
only the creation code to GitHub's fixed HTTPS conversion endpoint. It never
fetches the supplied URL. Earlier app versions lack saved endpoint metadata;
their pending state still must match exactly. The callback URL, code and secrets
are not included in source, status messages or logs. Expired setup must restart.

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

## Updated-client online-only acceptance

New Meridian clients accept only device-matched, signed SFLIC3 policies from
the online catalog. Local SFLIC1/SFLIC2 activation codes and format-2 catalog
records do not unlock the screen reader or licensed features, regardless of
whether a legacy code was saved before an in-place APK update. The registration
screen no longer offers a code-paste action. An owner must reissue affected
devices with an online SFLIC3 policy. A previously verified SFLIC3 policy
remains usable during the established seven-day offline lease. These
requirements do not alter the behavior of APK versions already installed.

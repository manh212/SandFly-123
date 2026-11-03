# Meridian administration tool

The new **Meridian Quản trị** app installs as
`com.sandfly.offlinelicenser.v2` alongside the previous issuer. It uses stable
APK signing, R8, hardware-backed license signing, per-issuance authentication,
encrypted local records and one-time browser sign-in to GitHub.

Download `meridian-admin.apk` from the [dev release](https://github.com/manh212/SandFly-123/releases/tag/dev).
Do not uninstall or clear the old issuer to install this app. Do not uninstall
or clear the new app after creating its key: the private key is non-exportable.

Follow [Activation and recovery](../../docs/activation-recovery.md) for the
initial key registration, browser connection, migration and recovery steps. The first
connection publishes only the public verification key and triggers a Meridian
build. Install that newer Meridian build before using SFLIC2 registrations.

The workflow **Build Meridian Admin APK** runs focused protocol and publication
tests, builds a non-debuggable R8 APK, verifies its signing certificate and DEX
obfuscation, and publishes it as a separate asset on the rolling dev release.
APK signing material remains in the existing Actions secrets; license signing
private keys remain on the administrator phone. No private license signing key,
GitHub credential or recipient name is committed to the public repository.

The browser connection uses GitHub's App Manifest registration flow. The owner
signs in, confirms creation of a private GitHub App, and installs it on only
SandFly-123. Configuration returns directly to a temporary loopback listener
on the phone; no hosted server or pasted token is needed. A standard Custom Tab
opens for a result in the administration activity's task. The phone checks the
actual GitHub installation every five seconds while setup is open. Once GitHub
confirms the owner, app, selected repository and write permission, the native
activity finishes its browser activity and starts synchronization. Completion
therefore does not depend on GitHub's final setup-page redirect or on a second
HTTP-to-custom-scheme redirect. Returning manually to the app also resumes the
same encrypted pending connection. All later uploads use the GitHub API in the
background.

A temporary data-sync foreground service owns the receiver's lifetime while the
browser is in front. Its notification opens the tool or cancels setup; it stops
after completion, explicit cancellation or expiry. The encrypted pending session
includes its port, route, state and deadline, so a sticky service restart rebinds
the original endpoint. A browser activity result alone no longer closes the
receiver. Setup status distinguishes waiting for creation from waiting for
installation. Android foreground-service permissions are normal manifest
permissions; the tool does not request a notification runtime grant.

If the browser cannot reach the receiver, copy its **this setup page** link,
return to the tool and select **Tiếp tục từ liên kết đã sao chép**. This reads
the clipboard only on that explicit action, validates the original unexpired
state and endpoint, and exchanges the code directly with GitHub. It cannot
complete another phone's session or accept arbitrary URLs. Old pending sessions
without endpoint metadata require the same stored state. Expired sessions must
be restarted. Do not publish these temporary callback links.

GitHub App credentials are encrypted on the administrator phone, separate from
the non-exportable license issuer key. Each synchronization requests a fresh,
one-hour installation token limited to this repository and Contents: write.
Registration cancellation, process termination during setup, GitHub consent,
browser closure and hardware-backed storage require phone testing.

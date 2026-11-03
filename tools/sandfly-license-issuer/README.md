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
on the phone; no hosted server or pasted token is needed. Auth Tab captures the
final custom-scheme return and closes; older browsers use the Custom Tab
callback to the existing administration activity. All later uploads use the
GitHub API in the background.

GitHub App credentials are encrypted on the administrator phone, separate from
the non-exportable license issuer key. Each synchronization requests a fresh,
one-hour installation token limited to this repository and Contents: write.
Registration cancellation, process termination during setup, GitHub consent,
Auth Tab fallback and hardware-backed storage require phone testing.

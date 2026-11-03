# Meridian administration tool

The new **Meridian Quản trị** app installs as
`com.sandfly.offlinelicenser.v2` alongside the previous issuer. It uses stable
APK signing, R8, hardware-backed license signing, per-issuance authentication,
encrypted local records and a fine-grained GitHub token entered on the phone.

Download `meridian-admin.apk` from the [dev release](https://github.com/manh212/SandFly-123/releases/tag/dev).
Do not uninstall or clear the old issuer to install this app. Do not uninstall
or clear the new app after creating its key: the private key is non-exportable.

Follow [Activation and recovery](../../docs/activation-recovery.md) for the
initial key registration, token setup, migration and recovery steps. The first
connection publishes only the public verification key and triggers a Meridian
build. Install that newer Meridian build before using SFLIC2 registrations.

The workflow **Build Meridian Admin APK** runs focused protocol and publication
tests, builds a non-debuggable R8 APK, verifies its signing certificate and DEX
obfuscation, and publishes it as a separate asset on the rolling dev release.
APK signing material remains in the existing Actions secrets; license signing
private keys remain on the administrator phone. No private license signing key,
GitHub token or recipient name is committed to the public repository.

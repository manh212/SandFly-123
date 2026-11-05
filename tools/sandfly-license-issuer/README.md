# Administration utility

Install `meridian-admin.apk` from the [development release](https://github.com/manh212/pkg-7f3c9a2e/releases/tag/dev). Update it in place. Do not uninstall it or clear its data: the hardware signing key cannot be restored from the repository.

Use the original administrator phone and existing signing identity. The repository selected during GitHub setup is `pkg-7f3c9a2e`. Existing installations remain tied to the same repository identity after a rename; older admin APKs must be updated to use its new address.

See [owner setup](../../docs/activation-recovery.md), [device administration](../../docs/device-license-management.md) and [support setup](../../docs/support-feedback.md).

GitHub Actions runs focused checks, builds the APK with R8, verifies its signature and publishes the development asset. Credentials and license signing keys remain on the administrator phone.

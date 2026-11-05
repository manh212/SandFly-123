# Distribution hardening

Both APKs enable R8 in debug and release builds. The distributed debug APKs are not debuggable. Licensing, shared protocol and administration implementation classes can be renamed, optimized or removed. The main APK also obfuscates its update implementation. Android entry points, framework callbacks and the settings fragment loaded by name are preserved. Existing keep rules outside these areas remain in place to protect reflection and native integrations.

The source profile at `activation/assets/meridian-service.json` remains editable. Both builds generate an encoded asset instead of packaging that plaintext profile. Repository and service addresses use a runtime decoder; its keep rule prevents optimization from folding decoded values into constants while allowing its name to change. The encoding is reversible XOR masking with Base64 transport, not secret-key encryption.

CI inspects DEX mappings and rejects exposed implementation names, plaintext distribution addresses and an unencoded or mismatched service asset before publishing. R8 mapping files are not included in APKs or public artifacts. Tests cover endpoint reconstruction, service configuration validation and existing licensing protocols.

The repository is public. Encoding increases the work needed for a static string search, but URLs remain observable at runtime and in network inspection. A repository rename does not erase Git history, upstream fork relationships, indexed copies or GitHub redirects. It does not provide authorization or replace signature verification. TLS validation, signed policy checks, administrator permissions and APK signing identities remain unchanged.

Update both APKs after a repository rename, especially the administrator app, whose credential-bearing API requests intentionally do not follow redirects. Already installed APKs cannot acquire new endpoint constants without an update. Do not reuse the old repository name while old links depend on GitHub redirects. See [owner setup](activation-recovery.md) and [device administration](device-license-management.md).

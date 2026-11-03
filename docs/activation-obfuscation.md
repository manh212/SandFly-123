# Activation obfuscation

Both Meridian build types enable R8 for the licensing package, including the
device license manager, protocol verification, APK signature verification,
activation reminder and activation screen implementation. Code outside that
package retains the existing keep policy. The activation activity relies on
the Android component rules instead of a rule keeping all its members.

Both debug and release builds of the issuer enable R8. The debug APK distributed
by its workflow is not debuggable. Issuer key handling, protocol signing and
the implementation methods and fields of its activity can be renamed,
optimized or removed by R8.

Android manifest component names and required framework callbacks are retained.
They are entry points, not exemptions for the implementation inside them.
Movable implementation classes are repackaged, and source file metadata uses
the neutral name `SourceFile`.

Both workflows inspect the actual APK DEX classes and R8 mapping before
publishing an APK. The check rejects retained implementation class names and
selected private activity member names. R8 mapping files are not included in
the APK or uploaded as public artifacts.

Obfuscation does not encrypt string constants, public verification keys or
network addresses, and does not make an APK impossible to modify. No issuer
private key or account token belongs in the APK. This build change does not
change the license format, registration behavior, APK signing identity or
GitHub synchronization. In particular, the existing issuer still requires its
original signing key for an in-place upgrade; do not uninstall it to install
an APK signed by another key.

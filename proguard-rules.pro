# Keep rules for the R8 trial on the published (debug) builds.
#
# Inherited from upstream's own R8 experiment in this codebase's history — 651b5c34 (the
# rules), ec687deb (the Room rule) and 5bfe37d0 (the ML Kit rules) — which upstream later
# reverted in d6588c07 after two runtime crashes. Both crash causes are covered by rules
# below, and this trial soaks on the dev channel before any stable release uses R8.
# The parts of upstream's setup that only served handwriting are not here: this fork has
# no handwriting and no digital-ink library.
#
# SandFly's own code finds classes and members by name: the compositor, settings
# fragments, JSON models and the native libraries that call back into Java. So it keeps
# all of its names and members, and R8 only optimizes it. The libraries are shrunk and
# optimized with their own rules.
-dontobfuscate
-keep,allowoptimization class com.google.android.accessibility.** { *; }
-keep,allowoptimization class com.google.android.libraries.accessibility.** { *; }
-keep,allowoptimization class com.google.android.marvin.** { *; }
-keep,allowoptimization class com.google.android.apps.common.** { *; }

# ML Kit creates its components by name, which its own rules don't keep. This fork's
# offline translation (ML Kit Translate) and text recognition register theirs this way.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# LiteRT-LM's native library calls back into its Java classes by name, and it has no
# rules. The on-device AI (Gemma) runs through it.
-keep class com.google.ai.edge.litertlm.** { *; }

# The speech API other apps bind to, which is named after Backtalk (AIDL in
# talkback/src/main/aidl/fyi/quin/backtalk).
-keep class fyi.quin.backtalk.** { *; }

# Scripts run in Lua through LuaJava, whose native library calls back into its Java
# classes by name, and scripts with the java permission reach Java classes by name
# through it.
-keep class party.iroiro.luajava.** { *; }

# Shizuku grants permissions through binder interfaces it finds by name.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# WorkManager, which ML Kit's model downloads use, creates its Room database by name.
# From ec687deb: without this, R8 removed WorkDatabase_Impl and the app crashed as it
# started.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# R8's optimizations break ML Kit's common code: its shared Random allows setSeed only
# while it is being constructed, and the optimized check throws in Random's constructor.
# Upstream hit the crash through handwriting (5bfe37d0), which this fork does not have,
# but the fork's translation and text recognition run on the same ML Kit common code, so
# ML Kit is kept unoptimized here too.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }

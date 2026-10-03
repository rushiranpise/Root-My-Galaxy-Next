# R8 keep rules for the release build.
#
# R8 is on for `release` only - a debug build stays unminified, so a stack trace from a phone is readable
# while something is being chased. What follows is everything in this app that is reached by a *name*
# rather than by a call, which is the only thing R8 cannot see and therefore the only thing it can break.
#
# The rule of thumb: if a name in this project is also written down in C, in a shell command, or in a
# string, it is kept whole - class and members - because renaming it turns a working phone into one where
# a step silently did nothing.

# ---------------------------------------------------------------------------------------------------
# 0. The three directives this file follows from upstream (BuSung-dev/Root-My-Galaxy PR #422, "Enable R8
# on release builds"). They are what makes the shrink this small: names are reused aggressively, access
# flags are widened so a member can be reached from wherever it ends up, and every renamed class is moved
# into one package so the package table stops carrying a directory per class.
#
# All three only ever apply to classes R8 was already going to rename, so the keep rules below are what
# decides which names survive - and what survives is checked, by name, in the built dex rather than being
# reasoned about here.
-repackageclasses
-allowaccessmodification
-overloadaggressively

# ---------------------------------------------------------------------------------------------------
# 1. The classes whose names are compiled into native code.
#
# A JNI symbol *is* the class and method name: `Java_dev_busung_s25uroot_UniversalRoot_nativeRunAll` and
# `Java_dev_busung_s25uroot_NativeProbe_run` are resolved by the JVM at link time, not by this project.
# Rename either and the load fails - for UniversalRoot that is `System.loadLibrary` throwing on the first
# tap of the chain, and for NativeProbe every reading that asks whether KernelSU is live, including the
# ones a boot takes with nobody watching.
#
# The whole class is kept rather than only the native method, because `UniversalRoot.Reporter` is a
# `fun interface` whose single method C calls by name: `reporter.h` resolves `report(String)` with
# `GetMethodID` against the object's own class, so an R8 rename of `report` makes that lookup throw inside
# `System.loadLibrary`, which reads as a broken build rather than as a missing callback.
# The first two are belt and braces: the Android toolchain's own rules already carry
# `-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }`, which keeps the
# *class and method names* of anything declaring a native method - so a JNI class survives even without a
# rule here. What that rule does not keep is `Reporter`, which declares no native method and is reached
# only by the `GetMethodID` above. Both are written out anyway, because the requirement is this project's
# and a default rule is not a promise: it is upstream's to change, and nothing here would fail loudly if
# it did.
-keep class dev.busung.s25uroot.UniversalRoot { *; }
-keep class dev.busung.s25uroot.UniversalRoot$Reporter { *; }
-keep class dev.busung.s25uroot.NativeProbe { *; }

# ---------------------------------------------------------------------------------------------------
# 2. The classes named by string from outside the app.
#
# `DfrInstall.MAIN_CLASS` is passed to `app_process` on a command line and `DfrInstall.STAGE_TWO_ACTIVITY`
# is started by `am` with the class name spelled out. Both run as root, outside this app's process, so
# neither is reached by a call R8 can follow - the string is the only link, and a renamed class is an
# inject that prints nothing and leaves `packages.xml` untouched.
#
# `InjectMain` is the one class here, because it is the only one of the two that lives in *this* module.
# The stage-two classes (`Stage2Activity`, `StageReceiver`, `StageHop`, `DmcVault`) are in the `dfr` module,
# which is a separate APK staged into this one as an asset - see the note at the bottom of this file for why
# that module is not minified, and for the check that keeps it that way.
-keep class dev.busung.s25uroot.dfr.InjectMain { *; }

# ---------------------------------------------------------------------------------------------------
# 3. The rest of the helper, whole.
#
# The `dfr` module's classes do not run in this app. They are injected into `system_server` and run there
# as the system uid, and the code that puts them there - `InjectMain`, kept above - is a port of another
# project's installer whose own documentation is explicit that its classes must not be obfuscated. Two
# further reasons of this project's own:
#
#  - `StageHop` and `DmcVault` reflect by name. The names they look up are the *platform's* (`ActivityManagerService`,
#    `LoadedApk`, `android.app.ActivityThread`) and are unaffected, but the class that hosts the reflection is
#    handed to the platform by string, and `DmcVault`'s Samsung vault is reached through a manager class the
#    helper names itself.
#  - `ControllerService` and `DmcBootReceiver` are declared in this module's manifest and started by the
#    platform, which resolves them by their manifest names.
#
# Kept as a package rule rather than a list, so a class added to the helper tomorrow is covered by the same
# reasoning today. The cost is that the helper's own code stays legible in the APK, which is the trade this
# project wants: the helper is the half that runs where a failure costs a boot.
-keep class dev.busung.s25uroot.dfr.** { *; }

# ---------------------------------------------------------------------------------------------------
# 4. Compose.
#
# The Compose runtime instantiates and reuses composable lambdas by name and holds them in generated
# classes; the toolchain ships the rules for it in its own consumer file, which is applied automatically.
# This is here only to say that nothing further is needed for the UI - the app's screens are all Compose
# and none of them is reached by string.

# ---------------------------------------------------------------------------------------------------
# 5. The helper module is deliberately **not** minified, and these rules say why rather than leaving it
# to be rediscovered.
#
# `dfr` builds the second APK, which this app carries as an asset and the phone installs as a system-uid
# app. R8 there would have to keep the `stage2` package whole - every one of its classes is either
# reflected on by name, scheduled by the platform from a string, or declared in its manifest, and the
# stage-two screen is a plain `Activity` whose views are found by id - and a keep of "the entire package"
# shrinks nothing. So the only thing R8 could do to the helper is break it, on the component that runs
# inside `system_server` where a failure costs a boot and leaves nothing readable behind.
#
# `verifyHelperKeeps` in this module's `build.gradle.kts` holds it: that task reads the helper APK out of
# the release APK's own `assets/stage2.apk` - the copy a phone would receive, not a build output that could
# be stale - and fails the build if the stage-two classes are no longer in it. So switching minification on
# for `dfr` without the keeps its stage two needs is caught here rather than on a phone.
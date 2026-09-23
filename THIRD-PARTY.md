# Third-party code in this repository

Two projects are vendored into this repository, both of them for the Dirty Frag root path. Neither
carries a license, so nothing here is granted on the original authors' behalf — this file exists so that
what came from where is written down rather than inferred from a diff, and so the next person to touch
these files knows which parts are theirs to change.

## DFReroot — https://github.com/polygraphene/DFReroot

No `LICENSE`, no `NOTICE`, and no SPDX header in any of the files below: all rights reserved by default.
Taken because the mechanism is the point and the alternative was not having it.

| Here | There | State |
|---|---|---|
| `app/src/main/java/dev/busung/s25uroot/dfr/Abx.kt` | `installer/…/Abx.kt` | Close to verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/PackagesXml.kt` | `installer/…/PackagesXml.kt` | Close to verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/SigKey.kt` | `installer/…/SigKey.kt` | Verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/SysKey.kt` | `installer/…/SysKey.kt` | Verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/InjectMain.kt` | `installer/…/InjectMain.kt` | Package name and default key package changed |
| `dfr/src/main/jni/exp.c`, `stage1.S`, `elf_parser.c`, `include.inc`, `logging.h`, `splicehelper.c` | `app/src/main/jni/…` | Verbatim |
| `dfr/src/main/jni/dirtyfrag-android*.ko`, `splicehelper` | built by their `build.sh` | Bytes, unchanged |
| `dfr/src/main/jni/CMakeLists.txt` | `app/src/main/jni/CMakeLists.txt` | Verbatim |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/StageHop.kt` | `app/…/StageHop.kt` | Comments rewritten, logic unchanged |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/StageReceiver.kt` | `app/…/StageReceiver.kt` | Codes named, else unchanged |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/KsudStage.kt` | `app/…/KsudStage.kt` | Destination and daemon sources changed |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Stage2Activity.kt` | `app/…/MainActivity.kt` | Rewritten in code rather than XML layouts |

**Ours, in the same flow:** `dfr/` as a Gradle module (their `app` module), `DfrInstall.kt`,
`DfrFlow.kt`, `DfrApk.kt`, `DfrUi.kt`, every test under `dfr` in both modules, and the decision of how
the flow is driven. Their two-APK split is forced by `sharedUserId="android.uid.system"` rather than
chosen — see the module comment in `settings.gradle.kts`.

## LSPromise — https://github.com/LSPosed/LSPromise

Also no license. One file, and it is the JNI bridge whose package name cannot change: `exp.c` registers
`Java_org_lsposed_lspromise_DirtyFrag_*` natives, so the Java class has to keep that package.

| Here | There |
|---|---|
| `dfr/src/main/java/org/lsposed/lspromise/DirtyFrag.java` | `…/DirtyFrag.java` |

## The kernel module

`lkm/permissive/` in the payload repository is **not** vendored from either project: it is written here
from the kernel's own headers, because the module this one replaces hardcodes the byte it writes and we
derive the offset per kernel instead. Its provenance notes are in that directory's own README.

## If either project ever grants a license

Replace this file's first paragraph and the header on each ported file with that license's terms. Until
then, these files are the only ones in the repository that are not Apache-2.0 that grant nothing, and
they should stay named as such.

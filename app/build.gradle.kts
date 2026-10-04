import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing material. CI passes it through environment variables; a local build
// can keep it in keystore/keystore.properties instead (that file is gitignored).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingProperty(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

// The base version, and the only place either number is written by hand. A release tag is
// `v$appVersionBase` and both workflows read this literal out of this file, so it has to stay a
// plain string here rather than being assembled from somewhere else.
val appVersionBase = "0.10"

// An offset under the version code, not a version of its own: the code is this plus the clock, and the
// only rule is that it may be raised and never lowered - lowering it would put a new build below an
// installed one and Android would refuse the install.
val appVersionCodeBase = 14

// The clock the version code is derived from, read through a value source so the reading counts as
// a build configuration input. Reading the clock directly is not enough: configuration cache
// entries outlive builds and store the value, so a local rebuild that changed only source files
// was handed the previous build's clock and reused its version code two different APKs under one
// identity. Being a configuration input means a changed reading invalidates the entry, so every
// build reconfigures; that reconfiguration is the price of a version code that is unique per build.
abstract class BuildClockValueSource : ValueSource<Long, ValueSourceParameters.None> {
    override fun obtain(): Long = System.currentTimeMillis()
}

// A version code that only ever grows, on every machine that builds this. A per-CI run counter
// would not be comparable with a local build, and Android refuses to install a lower version code
// over a higher one, which would break installing a local build over a CI build (or the reverse),
// so the number is seconds since 2026-01-01 UTC: unique per build everywhere and always larger
// than the build before it.
val appVersionCode =
    appVersionCodeBase +
        (providers.of(BuildClockValueSource::class) {}.get() / 1000L - 1_767_225_600L).toInt()

// Which build this is: the CI run that produced it, or the local commit it was built from. Two
// builds of the same version are otherwise indistinguishable on the phone, which is what this is
// for: Settings shows it and every run log starts with it.
val buildCommit: String? = System.getenv("GITHUB_SHA")
    ?.trim()
    ?.take(7)
    ?.takeIf { it.isNotEmpty() }
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short=7", "HEAD")
        }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
val buildLabel = listOfNotNull(
    System.getenv("GITHUB_RUN_NUMBER")?.takeIf { it.isNotBlank() }?.let { "ci.$it" } ?: "local",
    buildCommit,
).joinToString(".")
val appVersionName = "$appVersionBase+$buildLabel"

android {
    namespace = "dev.busung.s25uroot"
    compileSdk = 37

    sourceSets {
        // The launcher icon lives outside this module because two APKs ship it: this app and the
        // `:dfr` helper that gets installed as a system app. A copy in each module is an icon that
        // drifts without anyone noticing, which is what `StageTwoIdentityTest` holds this and the
        // helper's build file to.
        getByName("main").res.srcDir(rootProject.file("launcher-icon"))
    }

    defaultConfig {
        // This fork installs as its own app, beside the one it came from rather than over it: the two
        // are signed with different keys, so a shared id could never upgrade the other install, and a
        // unique one is what lets both be present while a fork finds its feet.
        //
        // The namespace above deliberately stays upstream's. It decides the Kotlin package, every
        // action string, both provider authorities and the R class, and moving it would touch the
        // whole tree to change nothing anyone can see - the id below is the install's identity, and it
        // is the only one Android checks.
        applicationId = "dev.rushiranpise.rmgnext"
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // VERSION_BASE is what the update check compares against a release tag; the build label is
        // the same string the version name carries, for showing on its own.
        buildConfigField("String", "VERSION_BASE", "\"$appVersionBase\"")
        buildConfigField("String", "BUILD_LABEL", "\"$buildLabel\"")

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingProperty("KEYSTORE_FILE", "storeFile")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storeType = signingProperty("KEYSTORE_TYPE", "storeType") ?: "PKCS12"
                storePassword = signingProperty("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingProperty("KEY_ALIAS", "keyAlias")
                keyPassword = signingProperty("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            // R8, on the release build only. A debug build stays unminified on purpose: every failure in
            // this app is diagnosed from a log written by a phone, and a stack trace that names `a.b.c` is
            // worth more there than a smaller APK.
            //
            // What R8 can break here is anything reached by a name rather than by a call - a JNI symbol,
            // a class handed to `app_process`, a receiver the platform instantiates, a `GetMethodID` in C.
            // `proguard-rules.pro` is the list of those and the reasoning for each; the built dex is checked
            // against that list by `verifyR8Keeps`, which runs on every release build rather than waiting for
            // a phone to find out.
            isMinifyEnabled = true
            // Resources too, which is what most of the shrink is: the app carries seven translations, the
            // Compose tooling metadata the build does not use, and a helper APK it *does* - an asset, so it
            // is not resource-shrunk, only the layouts, drawables and string tables beside it.
            //
            // Safe with a keep-list rather than a scan: `res/raw/keep.xml` names what a shrink would
            // otherwise remove because nothing in the dex appears to read it, and everything else here is
            // reached from a composable, which is a reference the shrinker can see.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Stated rather than inherited, which is how the PR this follows writes it: the debug build is
            // the one used to reproduce a failure from a phone. Still unminified, and named in the README's
            // build-identity table as the reason a debug APK is the right artifact to attach to a report.
            isDebuggable = true
            // Obfuscation is release-only, and these two lines are where that is decided rather than
            // inherited: `proguard-rules.pro` is not even named here, because the directives in it
            // (`-repackageclasses` above all) rename and move classes, and a debug APK exists to be the
            // legible one - the artifact a run log and a stack trace are read from.
            isMinifyEnabled = false
            isShrinkResources = false
            // Signed with the repository key when it is configured, and with the stock debug key when
            // it is not - so a developer without the keystore still builds a debug APK, and every build
            // this project distributes shares one signature: CI debug, CI release, tagged releases and
            // a local build all update over each other.
            //
            // This was the debug key alone, which is generated per machine and therefore different on
            // every CI runner: a debug APK built there could not be installed over the previous one, or
            // over the signed release APK, without an uninstall - so the debug APK published with a
            // pre-release was an artifact nobody could test with.
            signingConfigs.getByName("release").storeFile?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }

    // An unsigned release APK builds happily and then fails at install time, which is
    // how a mis-signed artifact once shipped. Refuse to build one instead.
    if (signingConfigs.getByName("release").storeFile == null &&
        gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    ) {
        throw GradleException(
            "Release signing is not configured: set KEYSTORE_FILE, KEYSTORE_PASSWORD, " +
                "KEY_ALIAS and KEY_PASSWORD, or create keystore/keystore.properties (see README)."
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        // The kernel test is an executable this project did not build and cannot rebuild, and the packager
        // strips debug symbols from every native library by default - which would mean the bytes on the phone
        // were not the bytes this project took, and the hash recorded of them in VulnerabilityBinaryTest would
        // be a statement about a file nothing ships. Keeping its symbols keeps the taken artifact intact.
        jniLibs.keepDebugSymbols += "**/libpoc64.so"
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/**
 * The names R8 must not touch, and why each one is on the list.
 *
 * Written here as the single source of truth for the check below. It is deliberately *not* parsed out of
 * `proguard-rules.pro`: the rules are what makes R8 keep them, and this is what proves it did - two
 * separate statements of the same requirement, so a rule that was dropped, misspelled, or never matched
 * anything is caught rather than being assumed to have worked.
 */
val r8KeptNames: List<Pair<String, String>> = listOf(
    // Compiled into a native library's symbol table: the JVM resolves these by name at link time.
    "dev.busung.s25uroot.UniversalRoot" to "the chain's JNI class - System.loadLibrary aborts without it",
    "dev.busung.s25uroot.UniversalRoot\$Reporter" to "the interface whose report(String) C calls by name",
    "dev.busung.s25uroot.NativeProbe" to "the JNI class behind every KernelSU-is-live reading",
    // Spelled out in a string and used outside this app's process.
    "dev.busung.s25uroot.dfr.InjectMain" to "the app_process entry point the certificate inject runs",
)

/**
 * The names that must survive in the *helper's* dex, which is a separate APK carried as an asset.
 *
 * The helper module is not minified today - see `proguard-rules.pro` section 5 - so this is a guard rather
 * than a repair: the classes the stage-two chain reaches by name are in an APK this app only *stages*, and
 * nothing in the app module's own build would notice if minification were switched on there.
 */
val helperKeptNames: List<Pair<String, String>> = listOf(
    // Reached by name in the app's own words: the helper names these to `am` and to the platform.
    "dev.busung.s25uroot.dfr.stage2.Stage2Activity" to "the screen the app opens by class name",
    "dev.busung.s25uroot.dfr.stage2.StageReceiver" to "the receiver StageHop schedules by name",
)

/**
 * Fails the release build if R8 renamed one of the names above.
 *
 * Reads the built APK's dex rather than the mapping file, because the mapping file says what R8 *intended*
 * and the dex is what the phone runs. `dexdump` comes from the same build-tools this build already uses,
 * and a descriptor string is exactly the thing an obfuscated class no longer has.
 *
 * Runs after `minifyReleaseWithR8` and before the APK is considered done, so a release that would break
 * on a phone never leaves CI. Cost is one `dexdump` over the app's own dex - a few seconds on a build that
 * just spent minutes minifying.
 */
// Resolved at configuration time into plain values, so the task body holds nothing from the build script.
// That is not tidiness: the configuration cache refuses to serialize a task that closed over the script,
// and a check nobody can run twice for free is a check that gets switched off.
val r8DexDump: String = run {
    val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
    val tools = sdk.resolve("build-tools").listFiles()
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name }
        ?.asReversed()
        .orEmpty()
    tools.flatMap { it.listFiles()?.toList().orEmpty() }
        .firstOrNull { it.name == "dexdump" || it.name == "dexdump.exe" }
        ?.absolutePath
        ?: error("dexdump is missing from $sdk/build-tools, so the dex cannot be read")
}

val verifyR8Keeps = tasks.register("verifyR8Keeps") {
    group = "verification"
    description = "Fails if R8 renamed a class this project reaches by name."
    val apkDirectory = layout.buildDirectory.dir("outputs/apk/release")
    val kept = r8KeptNames
    val dexdump = r8DexDump
    inputs.dir(apkDirectory)
    // Not up-to-date-able: this is a check, and the answer it gives is the point of running it.
    outputs.upToDateWhen { false }
    doLast {
        val apk = apkDirectory.get().asFile.listFiles()
            ?.firstOrNull { it.name.endsWith(".apk") }
            ?: error("no release APK to check in ${apkDirectory.get().asFile}")
        // Every dex in the APK, because a large app is split across several and the class could land in
        // any of them. Read straight out of the zip rather than through the project's `copy`, which would
        // be another reference to the build script inside a task body.
        val dex = File(apk.parentFile, "${apk.nameWithoutExtension}-dex").apply { deleteRecursively() }
        dex.mkdirs()
        val names = mutableListOf<File>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.startsWith("classes") || !entry.name.endsWith(".dex")) continue
                val target = File(dex, File(entry.name).name)
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                names += target
            }
        }
        check(names.isNotEmpty()) { "no dex was found in ${apk.name}" }
        val dump = names.joinToString("\n") { file ->
            val process = ProcessBuilder(dexdump, "-f", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            check(code == 0) { "dexdump failed on ${file.name} with exit code $code\n$text" }
            text
        }
        val missing = kept.filterNot { (name, _) -> dump.contains("'L${name.replace('.', '/')};'") }
        check(missing.isEmpty()) {
            "R8 renamed something this project reaches by name:\n" + missing.joinToString("\n") { (name, why) ->
                "  - $name ($why)"
            } + "\nAdd a keep rule to app/proguard-rules.pro, or stop reaching it by name."
        }
        logger.lifecycle("R8 kept all ${kept.size} names this project reaches by name")
    }
}

/**
 * The same reading, one APK further out: the helper's dex, out of the copy this app carries.
 *
 * The helper is a second APK staged into this module's assets and it is not minified today - `proguard-rules.pro`
 * section 5 says why - so this is a guard rather than a repair. It reads the artifact rather than the
 * sources, because a check on `dfr`'s sources cannot tell whether the APK that ships was built from them;
 * whatever the build puts in `assets/stage2.apk` is what has to still name the stage-two classes the app
 * opens by string.
 *
 * Kept separate from [verifyR8Keeps] rather than folded in, because the two failures are answered in two
 * different build files - and they are two questions: is this app's dex intact, and does the helper this
 * app carries still have the classes the app names.
 */
val verifyHelperKeeps = tasks.register("verifyHelperKeeps") {
    group = "verification"
    description = "Fails if the helper APK this app carries lost a class reached by name."
    val apkDirectory = layout.buildDirectory.dir("outputs/apk/release")
    val kept = helperKeptNames
    val dexdump = r8DexDump
    inputs.dir(apkDirectory)
    outputs.upToDateWhen { false }
    doLast {
        val apk = apkDirectory.get().asFile.listFiles()
            ?.firstOrNull { it.name.endsWith(".apk") }
            ?: error("no release APK to check in ${apkDirectory.get().asFile}")
        val staged = File(apk.parentFile, "${apk.nameWithoutExtension}-helper")
            .apply { deleteRecursively() }
        staged.mkdirs()
        val helperApk = File(staged, "stage2.apk")
        // The helper is read out of the APK's own asset table, which is the copy the phone would receive -
        // not out of `dfr`'s build directory, where a stale artifact could still be sitting.
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("assets/stage2.apk")
                ?: error("${apk.name} carries no assets/stage2.apk, so the helper was not staged into it")
            zip.getInputStream(entry).use { input ->
                helperApk.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val dex = File(staged, "dex").apply { mkdirs() }
        val names = mutableListOf<File>()
        ZipFile(helperApk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.startsWith("classes") || !entry.name.endsWith(".dex")) continue
                val target = File(dex, File(entry.name).name)
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                names += target
            }
        }
        check(names.isNotEmpty()) { "the helper APK carries no dex at all" }
        val dump = names.joinToString("\n") { file ->
            val process = ProcessBuilder(dexdump, "-f", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            check(code == 0) { "dexdump failed on the helper's ${file.name} with exit code $code\n$text" }
            text
        }
        val missing = kept.filterNot { (name, _) -> dump.contains("'L${name.replace('.', '/')};'") }
        check(missing.isEmpty()) {
            "the helper APK this app carries no longer has a class it reaches by name:\n" +
            missing.joinToString("\n") { (name, why) -> "  - $name ($why)" } +
            "\nCheck whether :dfr started minifying without the keeps its stage two needs."
        }
        logger.lifecycle("the helper still has all ${kept.size} names the app reaches by name")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha24")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.materialkolor:material-kolor:4.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Pairing with the device's own wireless debugging speaks the ADB protocol's TLS, which needs a
    // client certificate and the `adb` ALPN; Conscrypt as shipped cannot be asked for that shape, so
    // the TLS client, the certificate builder and the SPAKE2 pairing exchange come from Bouncy Castle.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("org.bouncycastle:bctls-jdk18on:1.80")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Local JVM tests otherwise get Android's stub org.json, whose methods throw "not mocked", so
    // SupportManifest, which deliberately uses org.json, could not be tested on its real semantics.
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

/**
 * Copies the stage two into this APK's assets, one build type at a time.
 *
 * The stage two is a second APK that cannot be merged into this one - it declares
 * `sharedUserId="android.uid.system"`, which is an application-level identity - so something has to
 * carry it, and carrying it here is what makes the flow one screen with no file picking in it. The
 * installer this was ported from does the same thing for the same reason: its `build.sh` copies the
 * staged APK into its own assets.
 *
 * A generated directory rather than a file dropped into `src/main/assets`, so the artifact is never in
 * git: the bytes only exist as the output of `:dfr`, and a stale copy cannot outlive the build that made
 * it.
 */
abstract class StageTwoAsset : DefaultTask() {
    @get:InputFile
    abstract val apk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val directory = outputDir.get().asFile
        directory.mkdirs()
        apk.get().asFile.copyTo(File(directory, ASSET_NAME), overwrite = true)
        logger.lifecycle("staged $ASSET_NAME from ${apk.get().asFile}")
    }

    companion object {
        /** The name the app reads it by; `DfrApk` hardcodes the same one. */
        const val ASSET_NAME = "stage2.apk"
    }
}

// The check runs on every release build, whether it was asked for by name or not: a release APK that
// cannot inject its certificate is worse than one that failed to build, and the failure would only be
// found on a phone.
androidComponents {
    onVariants { variant ->
        if (variant.buildType == "release") {
            val checks = listOf(tasks.named("verifyR8Keeps"), tasks.named("verifyHelperKeeps"))
            tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(checks) }
        }
        // Nullable in the API, and meaningless here: the artifact copied is chosen by build type, so a
        // variant without one has nothing to copy rather than a default to fall back on.
        val buildType = requireNotNull(variant.buildType) {
            "variant ${variant.name} has no build type, so no stage two can be staged for it"
        }
        val capitalised = buildType.replaceFirstChar { it.uppercase() }
        val stage = tasks.register<StageTwoAsset>("stageStageTwo$capitalised") {
            // The artifact this copies is :dfr's, so the build it comes from must have run first -
            // and the build type has to match, because a release app whose stage two was built
            // unsigned would offer an install that can never succeed.
            dependsOn(":dfr:assemble$capitalised")
            apk.set(
                project(":dfr").layout.buildDirectory
                    .file("outputs/apk/$buildType/dfr-$buildType.apk"),
            )
            outputDir.set(layout.buildDirectory.dir("generated/stage2/$buildType"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(stage, StageTwoAsset::outputDir)
    }
}

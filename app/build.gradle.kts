import java.io.File
import java.util.Properties
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
val appVersionBase = "0.4"

// An offset under the version code, not a version of its own: the code is this plus the clock, and the
// only rule is that it may be raised and never lowered - lowering it would put a new build below an
// installed one and Android would refuse the install.
val appVersionCodeBase = 13

// The clock the version code is derived from, read through a value source so the reading counts as
// a build configuration input. Reading the clock directly is not enough: configuration cache
// entries outlive builds and store the value, so a local rebuild that changed only source files
// was handed the previous build's clock and reused its version code — two different APKs under one
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
        }
        debug {
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

androidComponents {
    onVariants { variant ->
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

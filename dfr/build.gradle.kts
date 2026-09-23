import java.util.Properties

plugins {
    id("com.android.application")
}

// The same signing material the app module uses: CI passes it through environment variables, a local
// build keeps it in keystore/keystore.properties. It has to be the same key, because the key that gets
// its certificate injected into `android.uid.system` is the key this APK is signed with - and if both
// APKs share one, the installer's default target (this app's own signer) covers this one, so nothing
// has to be picked by hand before the inject.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingProperty(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.busung.s25uroot.dfr.stage2"
    compileSdk = 37

    sourceSets {
        // The same launcher icon the app draws, from the same files: the two APKs are one product and
        // a user sees them side by side in a launcher. Read from the repository root rather than
        // copied into this module, so a change to the icon cannot land on one of them only.
        getByName("main").res.srcDir(rootProject.file("launcher-icon"))
    }

    defaultConfig {
        // The app's own id with `.helper` on it: the two are different apps to Package Manager - this
        // one declares `android.uid.system` as its shared user and the app does not - but they are one
        // product, and a user who installs this by mistake can tell from the id where it came from.
        // The name has to match `DfrInstall.STAGE_TWO_PACKAGE`, which is what `pm install` and the
        // uninstall name; `StageTwoIdentityTest` holds the two together.
        applicationId = "dev.rushiranpise.rmgnext.helper"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Stage one is AArch64 assembly (stage1.S), so the artifact is arm64-only; everything else in
        // the chain is portable, and on a device without that ABI the native load fails with a message
        // rather than at build time.
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
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
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
            // Signed with the repository key when one is configured, exactly as the app module is, so
            // a debug stage-2 and a debug app share a signer and one inject covers both.
            signingConfigs.getByName("release").storeFile?.let {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        // Loaded by path from the extracted native library directory, so the libraries must be
        // extracted rather than mapped out of the APK.
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

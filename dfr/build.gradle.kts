import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * The helper's version code, taken from the bytes the helper is built out of.
 *
 * A number written here by hand is a number somebody has to remember to change, and forgetting it is the
 * failure the app's stale-helper check exists to catch: the phone keeps the copy it installed last time,
 * Package Manager reports it as installed and healthy - it really is - and the run that follows fails
 * inside the exploit, in the shape of the exploit having failed. So the code is a digest of this module's
 * own sources, which are the same input its behaviour is built from: it changes exactly when the helper
 * does, whether or not anybody was watching, and two builds of the same sources get the same code because
 * they are the same helper.
 *
 * That last property is what makes equality the right comparison rather than order - see
 * `stageTwoBuild` in the app, which asks whether the copy on the phone *is* this one and never which is
 * newer. A code that only ever went up would have to be bumped for a comment as well as for a fix, and
 * the app would ask for an install that changes nothing.
 *
 * [SCOPE] is the resources, the manifest and every source the APK carries. Files are read in path order
 * so the digest does not depend on how the filesystem enumerates them, and the path is part of the digest
 * so moving a file is a change - because it can be one: this module's own manifest decides the process it
 * runs in.
 */
val helperVersionCode: Int = run {
    val digest = MessageDigest.getInstance("SHA-256")
    fileTree("src/main") {
        include("**/*.kt", "**/*.java", "**/*.c", "**/*.h", "**/*.S", "**/*.xml", "**/*.txt")
    }.files.sortedBy { it.absolutePath }.forEach { file ->
        digest.update(file.relativeTo(projectDir).invariantSeparatorsPath.toByteArray())
        digest.update(file.readBytes())
    }
    // 31 bits of the digest, kept inside the range every installer accepts, and never zero: a version
    // code of zero is the one value Package Manager treats as unset.
    (ByteBuffer.wrap(digest.digest(), 0, 4).int and 0x3FFFFFFF) + 1
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
        versionCode = helperVersionCode
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

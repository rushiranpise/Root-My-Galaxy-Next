package dev.busung.s25uroot.dfr

import android.content.Context
import java.io.File

/**
 * The stage-two APK this app installs, unpacked out of its own assets.
 *
 * There is one helper and this is it. The APK has to be built from this repository - its manifest is
 * what makes it a system app - and `app/build.gradle.kts` stages the `:dfr` module's artifact into these
 * assets for the build type being built, so the copy here is the one this app's own signing certificate
 * covers. The two names are held together by being the only writer and the only reader.
 *
 * ## Why there is no way to hand it a file
 *
 * There was: a picker, a copy of the chosen APK in app storage, and the pick winning over the bundle on
 * every later visit. That is not a testing convenience, it is a trap with no exit. The pick was restored
 * from storage at every dialog opening, nothing ever cleared it, and the bundle's own reasoning is the
 * reason it had to go - the bundled copy is unpacked on every call rather than cached, because
 *
 * > an unpacked copy that outlived an update would install the previous stage two - the one failure this
 * > would be hardest to notice, since both install cleanly.
 *
 * A stored pick is exactly that copy, except permanent and invisible: the dialog could only name it
 * `picked.apk`, and an app update that changed the helper it ships would have gone on installing the old
 * one. So a helper built from another commit reaches a phone by building this app, which is the same
 * thing CI does.
 */
/**
 * This build's helper APK, or the reason there is none.
 *
 * A pair rather than a nullable file, because the two ways this can come back empty are answered by
 * different actions: a build that carries no helper is a build to replace, and an APK that is in the
 * assets and could not be written to app storage is a phone with no room. A `null` said both, and the
 * screen said "no helper APK in its assets" for either - which is the wrong sentence for a full disk, and
 * the wrong action with it.
 */
internal data class DfrBundled(
    /** The unpacked APK, or null when [availability] is anything but [DfrHelperAvailability.Ready]. */
    val file: File?,
    val availability: DfrHelperAvailability,
)

/**
 * Which of the three cases a read of this build's helper came to.
 *
 * Separate from the read itself so it can be tested without an `APK` to unpack: the failure this exists
 * to prevent is a decision, not a file.
 */
internal fun classifyHelper(bytes: ByteArray?, written: Boolean): DfrHelperAvailability = when {
    // No asset, or an empty one, which is what a build with no `:dfr` artifact staged into it looks like.
    bytes == null || bytes.isEmpty() -> DfrHelperAvailability.NotInBuild
    // The bytes are there and did not reach app storage: a full disk, or storage this app may not write
    // to. The APK is in this build, so nothing about the build needs changing.
    !written -> DfrHelperAvailability.Unwritable
    else -> DfrHelperAvailability.Ready
}

object DfrApk {
    private const val DIRECTORY = "dfr-apk"

    /**
     * The name `app/build.gradle.kts` stages the `:dfr` artifact under.
     *
     * Not read from the build: this is an asset *name* inside the app's own APK, and the Gradle task that
     * writes it and this object that reads it are the only two things that ever name it. Changing one
     * without the other is a helper that is present and unreadable, which the dialog reports.
     */
    private const val BUNDLED_ASSET = "stage2.apk"
    private const val BUNDLED_FILE = "bundled.apk"

    /** The stage two this app ships, or why there is not one. */
    internal fun bundled(context: Context): DfrBundled {
        val destination = File(directory(context).apply { mkdirs() }, BUNDLED_FILE)
        val bytes = runCatching {
            context.assets.open(BUNDLED_ASSET).use { it.readBytes() }
        }.getOrNull()
        val written = bytes?.takeIf { it.isNotEmpty() }?.let { data ->
            runCatching {
                destination.writeBytes(data)
                destination.takeIf { it.isFile && it.length() > 0L }
            }.getOrNull()
        }
        return DfrBundled(written, classifyHelper(bytes, written != null))
    }

    /**
     * Deletes the copy of a hand-picked stage two left by a build that still had the picker.
     *
     * Nothing reads it any more, so it would sit in app storage for the life of the install - a whole
     * helper APK that no screen can name and no clean-up removes. Called from the flow's own read, which
     * is the one place guaranteed to run before anybody could wonder where their storage went.
     */
    fun discardPickedCopy(context: Context) {
        val directory = directory(context)
        File(directory, "picked.apk").delete()
        File(directory, "picked.apk.part").delete()
    }

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)
}

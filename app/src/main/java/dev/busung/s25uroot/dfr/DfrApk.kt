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

    /** The stage two this app ships, or null when this build has no bundle in its assets. */
    fun bundled(context: Context): File? {
        val destination = File(directory(context).apply { mkdirs() }, BUNDLED_FILE)
        val bytes = runCatching {
            context.assets.open(BUNDLED_ASSET).use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            destination.writeBytes(bytes)
            destination.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
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

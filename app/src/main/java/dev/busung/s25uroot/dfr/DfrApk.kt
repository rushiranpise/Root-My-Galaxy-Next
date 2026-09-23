package dev.busung.s25uroot.dfr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.busung.s25uroot.R
import java.io.File
import java.io.FileOutputStream

/**
 * The APK whose signing certificate is injected, copied where the root process can read it.
 *
 * The installer runs as `app_process` with root, outside this app's process and without any grant on
 * the document the user picked in the file chooser - a content URI is meaningless there and a
 * persisted grant can be revoked or its provider uninstalled. So the bytes are copied into app
 * storage at pick time, which is what makes the picked file usable at all; the copy is validated only
 * as far as "this is a zip with an APK's shape", because the key itself is read from the file by the
 * installer through PackageManager, which is the authority on what an APK's signer is.
 *
 * Two APKs exist at the end of the flow and they are not interchangeable: the one named here is the
 * APK that will *run* as `android.uid.system` after the reboot, and it is its own certificate that
 * has to be in `pastSigs`. Pointing this at the wrong file injects a key that never gets used, and
 * the install afterwards fails the signature check with nothing in this app's log to explain it.
 */
object DfrApk {
    private const val DIRECTORY = "dfr-apk"
    private const val FILE_NAME = "picked.apk"
    private const val PART_SUFFIX = ".part"

    /** An APK is a zip; the biggest Android apps are well inside this. */
    const val MAX_BYTES = 512L * 1024 * 1024

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** The stored APK, or null when none was picked or the stored file is gone. */
    fun file(context: Context): File? = File(directory(context), FILE_NAME)
        .takeIf { it.isFile && it.length() > 0L }

    /** Copies [uri] in and returns the file the installer reads its key from. */
    fun import(context: Context, uri: Uri): File {
        val name = queryDisplayName(context, uri) ?: FILE_NAME
        require(name.endsWith(".apk", ignoreCase = true) || name == FILE_NAME) {
            context.getString(R.string.dfr_not_apk)
        }
        val destination = File(directory(context).apply { mkdirs() }, FILE_NAME)
        val temporary = File(destination.parentFile, "$FILE_NAME$PART_SUFFIX")
        try {
            val total = copyInto(context, uri, temporary)
            require(total > 0L && startsWithZip(temporary)) {
                context.getString(R.string.dfr_not_apk)
            }
            require(destination.exists().not() || destination.delete()) {
                context.getString(R.string.repo_finalize_failed, name)
            }
            require(temporary.renameTo(destination)) {
                context.getString(R.string.repo_finalize_failed, name)
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        return destination
    }

    fun clear(context: Context) {
        val directory = directory(context)
        File(directory, FILE_NAME).delete()
        File(directory, "$FILE_NAME$PART_SUFFIX").delete()
    }

    private fun copyInto(context: Context, uri: Uri, temporary: File): Long {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { context.getString(R.string.local_payload_read_failed) }
            FileOutputStream(temporary).use { output ->
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { context.getString(R.string.dfr_apk_too_large) }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
                return total
            }
        }
    }

    private fun startsWithZip(file: File): Boolean = file.inputStream().use { stream ->
        val header = ByteArray(ZIP_MAGIC.size)
        stream.read(header) == header.size && header.contentEquals(ZIP_MAGIC)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)
}

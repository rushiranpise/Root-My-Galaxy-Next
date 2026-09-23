package dev.busung.s25uroot

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * A kernel module the user imported from the device, for testing one the app cannot fetch yet.
 *
 * The same shape as [LocalPayload] and for the same reasons: the file is copied into app storage at
 * import time rather than kept as a document URI, because the load may be started from somewhere with
 * no activity grant on a picked document, and a persisted URI grant can be revoked or its provider
 * uninstalled long after the file was chosen. It is validated before it can replace a module - `.ko`
 * name, a size cap, an ELF header - and a rejected file leaves the previous one in place.
 *
 * It is deliberately not the payload importer with a different extension. A `.so` here is a userspace
 * exploit that a run stages under its own name; a `.ko` is a kernel module that `insmod` loads from
 * wherever it sits, and the two must not be interchangeable: pointing a run at a module, or handing
 * `insmod` an exploit, is a mistake that fails in a way that reads as the exploit's fault.
 */
object LocalModule {
    private const val DIRECTORY = "local-module"
    private const val FILE_NAME = "rmg-permissive.ko"
    private const val PART_SUFFIX = ".part"

    /** A kernel module is small; anything near this is not one. */
    const val MAX_BYTES = 4L * 1024 * 1024

    private val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    /** The imported module, or null when none is set or the stored file is gone. */
    fun file(context: Context): File? = File(directory(context), FILE_NAME)
        .takeIf { it.isFile && it.length() > 0L }

    /** The name the picker reported, for the UI; falls back to the stored file name. */
    fun displayName(context: Context): String? = file(context)?.let { stored ->
        AppPreferences.localModuleName(context) ?: stored.name
    }

    /**
     * Reads [uri] into app storage and returns the name to show for it. Throws with a user-facing
     * message when the file is unusable; the stored module is only replaced by a completed copy.
     */
    fun import(context: Context, uri: Uri): String {
        val name = queryDisplayName(context, uri) ?: FILE_NAME
        require(isAcceptedName(name)) { context.getString(R.string.local_module_not_ko) }
        val destination = File(directory(context).apply { mkdirs() }, FILE_NAME)
        val temporary = File(destination.parentFile, "$FILE_NAME$PART_SUFFIX")
        try {
            val total = copyInto(context, uri, temporary)
            require(total > 0L && startsWithElfMagic(temporary)) {
                context.getString(R.string.local_module_not_elf)
            }
            require(temporary.renameToReplacing(destination)) {
                context.getString(R.string.repo_finalize_failed, name)
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        AppPreferences.setLocalModuleName(context, name)
        return name
    }

    fun clear(context: Context) {
        val directory = directory(context)
        File(directory, FILE_NAME).delete()
        File(directory, "$FILE_NAME$PART_SUFFIX").delete()
        AppPreferences.setLocalModuleName(context, null)
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
                    require(isWithinLimit(total)) { context.getString(R.string.local_module_too_large) }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
                return total
            }
        }
    }

    private fun startsWithElfMagic(file: File): Boolean = file.inputStream().use { stream ->
        val header = ByteArray(ELF_MAGIC.size)
        stream.read(header) == header.size && isElf(header)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)

    /** Rename that refuses to nest: a directory named like the destination is not silently kept. */
    private fun File.renameToReplacing(destination: File): Boolean {
        if (destination.exists()) destination.delete()
        return renameTo(destination)
    }

    /** The picker returns whatever name the provider reports, so the extension is checked. */
    internal fun isAcceptedName(name: String): Boolean = name.endsWith(".ko", ignoreCase = true)

    internal fun isWithinLimit(total: Long): Boolean = total <= MAX_BYTES

    /** A module that is not an ELF object cannot be loaded at all, so it is refused up front. */
    internal fun isElf(header: ByteArray): Boolean =
        header.size >= ELF_MAGIC.size && header.copyOf(ELF_MAGIC.size).contentEquals(ELF_MAGIC)
}

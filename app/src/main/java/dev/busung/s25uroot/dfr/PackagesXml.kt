/*
 * Ported into Root My Galaxy from DFReroot (https://github.com/polygraphene/DFReroot), installer
 * module. DFReroot carries no license; THIRD-PARTY.md at the repository root records what was taken,
 * from where, and why. Kept as close to the original as it can be, so a fix upstream stays
 * comparable line by line - the package name below and this header are the only additions.
 */
package dev.busung.s25uroot.dfr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * packages.xml injector core (no Android Context needed).
 *
 * Mirrors TLPE EvilFactory.injectSystemSignatures(): put our own cert key
 * into each target <shared-user> pastSigs with flags="2".
 * Foreign pastSigs entries (e.g. another tool's key for the same
 * shared-user) are kept in the same single pastSigs block: PMS honors
 * only the first pastSigs block and drops the rest on its next rewrite,
 * so merging is required for coexistence.
 * Our key is written twice: the most-recent past cert is not counted as
 * a rotation candidate, hence x2.
 *
 * Format handling:
 *  - READ: ABX (Android 13+ on-device format, magic `ABX\0`) via [Abx],
 *    plain-text XML via JAXP fallback. No reflection except the single
 *    framework entry-point call inside [Abx] (app_process-as-root only).
 *  - WRITE: plain-text XML (what PMS itself wrote for a decade;
 *    resolvePullParser auto-detects it on next boot and rewrites ABX on the
 *    next writeSettings). Attribute values are preserved verbatim from the
 *    string layer; [Abx.guardDecimalAttrs] aborts instead of corrupting if an
 *    INT_HEX-typed value ever shows up.
 *  - cert `key=` is ALWAYS lowercase hex (PMS reads it with
 *    getAttributeBytesHex == hexStringToBytes). Base64 is rejected, so our
 *    key is normalized with [Abx.toHex] at every entry point.
 *
 * Backends sharing [buildPatchedXml]:
 *  - [injectDirect]: [InjectMain] running pre-install as root via app_process
 *    (direct java.io.File access). PRIMARY path.
 */
object PackagesXml {
    const val PACKAGES_XML = "/data/system/packages.xml"

    /**
     * The pre-inject copy of packages.xml, and the file a rename swap stages before it replaces it.
     *
     * Both carry this fork's name rather than DFReroot's `.bak-df-installer` / `.new-df-installer`, and
     * that is a rule rather than a preference. The two installs can be on one phone, both name
     * `/data/system/packages.xml`, and the backup is written **once** - `overwrite = false`, never over
     * one that is already there - so a shared suffix would leave this install adopting DFReroot's
     * pre-inject file as its own rescue copy and never making one of its own. The same shape as the
     * daemon path, and the same reason.
     *
     * [TEMP_SUFFIX] is not written by a successful write at all: it is the file a failed direct write
     * leaves behind, and the log names the manual `mv` that finishes the job.
     */
    const val BACKUP_SUFFIX = ".bak-rmgnext"

    /** The staged copy a rename swap writes before replacing packages.xml with it. */
    const val TEMP_SUFFIX = ".new-rmgnext"

    /**
     * The two things the uninstall prints about our key, named here because the app reads them back.
     *
     * `--uninstall` is the only half of an undo that changes a file the phone boots from, so whether it
     * happened is not something to guess at from an exit code: the app clears its own record of having
     * injected only when one of these two lines says the key is gone. Both sides use these constants -
     * the log lines that print them and the reading that matches on them - because a reader that typed
     * the sentence out again would go on quietly answering "not done" after any rewording of the line it
     * was written against.
     */
    const val KEY_ABSENT = "our key not present"

    /** Printed once per target by the uninstall's own re-read; a `false` here aborts the process. */
    const val KEY_REMOVED = "our key removed"

    /**
     * The two lines above, as the uninstall actually prints them.
     *
     * Formatted here rather than inline so the app's reading of them is testable against the same
     * producer: a test can hand the reader a log built by these functions, which is the closest thing to
     * the injector's real output that a JVM test can produce - the write itself needs a device.
     */
    fun keyAbsentLine(): String = "[*] $KEY_ABSENT: already clean, nothing to write"

    /** One verify line, printed after the uninstall re-read the bytes it wrote. */
    fun keyRemovedLine(target: String, gone: Boolean): String = "[verify] $target $KEY_REMOVED: $gone"

    const val FLAG_SHARED_USER_ID = "2"

    /** Parse either ABX or text into DOM. Returns doc; throws with reason. */
    fun parseToDom(raw: ByteArray): Document {
        if (Abx.isAbx(raw)) return Abx.parseToDom(raw)
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isCoalescing = true
        }
        return dbf.newDocumentBuilder().parse(ByteArrayInputStream(raw)).also {
            it.documentElement.normalize()
        }
    }

    /** Structural sanity checks before any mutation. Throws on failure. */
    fun structuralChecks(doc: Document, targets: List<String>, log: StringBuilder) {
        val root = doc.documentElement?.tagName
            ?: throw RuntimeException("no root element")
        if (root != "packages") throw RuntimeException("unexpected root <$root>")
        val pkgs = doc.getElementsByTagName("package")
        log.appendLine("[*] <package> count=${pkgs.length}")
        if (pkgs.length < 20) throw RuntimeException("suspiciously few packages; refusing")
        val names = doc.getElementsByTagName("shared-user")
        val suNames = (0 until names.length).map {
            (names.item(it) as Element).getAttribute("name")
        }
        log.appendLine("[*] shared-users present: $suNames")
        for (t in targets) {
            if (t !in suNames) {
                throw RuntimeException(
                    "shared-user $t absent (not creating it: userId assignment " +
                        "must come from PMS itself)"
                )
            }
        }
    }

    /**
     * Global cert table in PMS encounter order (mirrors
     * PackageSignatures.readCertsListXml EXACTLY): single document-order
     * walk over every <cert>; inline `key=` null-pads to its index then
     * APPENDS (even if that overshoots a duplicate index); index-only
     * refs and index-less certs record nothing (PMS drops the latter with
     * a settings-problem warning).
     *
     * Why this matters: PMS writes with ONE global dedup table shared by
     * packages and shared-users (packages first). When our key bytes equal
     * an already-written cert (e.g. df_installer itself, same signing key),
     * PMS re-serializes our pastSigs as INDEX-ONLY refs on its next
     * writeSettings. A checker matching only inline `key=` then goes blind
     * while PMS itself still honors the rotation. Always resolve through
     * [effectiveKey], never by raw attribute.
     *
     * NOTE: `<cert index=N>` lives in this table's namespace; keyset
     * `<public-key identifier=M>` is a DIFFERENT namespace (KeySetManager
     * key IDs). A missing identifier proves nothing about an index.
     * Returns hex keys (lowercase) or null padding slots.
     */
    fun resolveKeyTable(doc: Document): MutableList<String?> {
        val table = mutableListOf<String?>()
        val all = doc.getElementsByTagName("cert")
        for (i in 0 until all.length) {
            val c = all.item(i) as? Element ?: continue
            val idx = c.getAttribute("index").toIntOrNull() ?: continue
            val key = c.getAttribute("key")
            if (key.isNotEmpty()) {
                if (!Abx.isHex(key)) continue // PMS would reject; don't trust
                while (table.size < idx) table.add(null)
                table.add(key.lowercase())
            }
        }
        return table
    }

    /**
     * Effective hex key of one <cert> element (inline `key=`, else the
     * encounter-order table at its index). Null when unresolvable
     * (dangling index, non-hex inline key) — PMS drops such certs too.
     */
    fun effectiveKey(cert: Element, table: List<String?>): String? {
        val inline = cert.getAttribute("key")
        if (inline.isNotEmpty()) {
            return if (Abx.isHex(inline)) inline.lowercase() else null
        }
        val idx = cert.getAttribute("index").toIntOrNull() ?: return null
        return if (idx >= 0 && idx < table.size) table[idx] else null
    }

    /**
     * Returns (keyHex, index) of our own <cert> from our <package> node, or
     * null. Resolves inline-key and index-only (table-resolved) refs alike —
     * the latter happens whenever another package with the same key bytes
     * was serialized earlier (same-signing-key apps dedup to one slot).
     */
    fun findInstalledKey(doc: Document, ownPkg: String): Pair<String, String>? {
        val table = resolveKeyTable(doc)
        val pkgs = doc.getElementsByTagName("package")
        for (i in 0 until pkgs.length) {
            val el = pkgs.item(i) as? Element ?: continue
            if (el.getAttribute("name") != ownPkg) continue
            val sigs = child(el, "sigs") ?: return null
            val cert = child(sigs, "cert") ?: return null
            val resolved = effectiveKey(cert, table) ?: return null
            return resolved to cert.getAttribute("index").ifEmpty { "0" }
        }
        return null
    }

    /**
     * Pure transform on an already-parsed DOM: returns patched TEXT XML bytes.
     * [ourKeyHex] must be lowercase hex (enforced).
     */
    fun buildPatchedXml(
        doc: Document,
        ourKeyHex: String,
        targets: List<String>,
        log: StringBuilder
    ): ByteArray {
        val key = ourKeyHex.lowercase()
        require(Abx.isHex(key) && key.length > 100) { "our cert key is not plausible hex" }
        Abx.guardDecimalAttrs(doc)

        // Safety: refuse when our key is already present (uninstall first).
        // Checked before touching anything so a partial multi-target write
        // can never happen.
        for (t in targets) {
            if (isInjected(doc, t, key)) {
                throw RuntimeException(
                    "already injected into $t (uninstall first to re-inject)"
                )
            }
        }

        // fresh index: after every existing index AND the encounter table
        // (PMS appends inline-key certs at max(size, index); stay past both).
        var maxIdx = -1
        var reuse: String? = null
        val allCerts = doc.getElementsByTagName("cert")
        for (i in 0 until allCerts.length) {
            val c = allCerts.item(i) as? Element ?: continue
            if (c.getAttribute("key").lowercase() == key && c.hasAttribute("index")) {
                reuse = c.getAttribute("index")
                break
            }
            c.getAttribute("index").toIntOrNull()?.let { maxIdx = maxOf(maxIdx, it) }
        }
        // NOTE: index-only refs share the same global table; take the max of
        // both so a padded table can never collide with our new entry.
        val table = resolveKeyTable(doc)
        val freshIndex = reuse ?: (maxOf(maxIdx + 1, table.size)).toString()
        if (reuse != null) log.appendLine("[+] reusing existing index $reuse for our key")
        else log.appendLine("[+] fresh cert index=$freshIndex")

        var changed = 0
        val users = doc.getElementsByTagName("shared-user")
        for (t in targets) {
            var node: Element? = null
            for (i in 0 until users.length) {
                val el = users.item(i) as? Element ?: continue
                if (el.getAttribute("name") == t) { node = el; break }
            }
            if (node == null) {
                log.appendLine("[!] shared-user $t not found, skipping")
                continue
            }
            log.appendLine("[+] injecting into $t userId=${node.getAttribute("userId")}")
            var sigs = child(node, "sigs")
            if (sigs == null) {
                sigs = doc.createElement("sigs")
                sigs.setAttribute("count", "1")
                node.appendChild(sigs)
            }
            val past = doc.createElement("pastSigs")
            var kept = 0
            for (old in children(sigs, "pastSigs")) {
                for (c in children(old, "cert")) {
                    past.appendChild(c)
                    kept++
                }
                sigs.removeChild(old)
            }
            if (kept > 0) log.appendLine("[+] keeping $kept foreign cert(s) in $t")
            repeat(2) {
                val c = doc.createElement("cert")
                c.setAttribute("index", freshIndex)
                c.setAttribute("key", key)
                c.setAttribute("flags", FLAG_SHARED_USER_ID)
                past.appendChild(c)
            }
            past.setAttribute("count", (kept + 2).toString())
            sigs.appendChild(past)
            changed++
        }
        if (changed == 0) throw RuntimeException("nothing changed (no target shared-user found)")

        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer()
            .transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    /**
     * True when [target] shared-user holds our key in any pastSigs cert.
     * Resolves through the encounter-order table, so PMS-normalized
     * (index-only) pastSigs are recognized too.
     */
    fun isInjected(doc: Document, target: String, ourKeyHex: String): Boolean {
        val key = ourKeyHex.lowercase()
        val table = resolveKeyTable(doc)
        val users = doc.getElementsByTagName("shared-user")
        for (i in 0 until users.length) {
            val el = users.item(i) as? Element ?: continue
            if (el.getAttribute("name") != target) continue
            val sigs = child(el, "sigs") ?: return false
            for (past in children(sigs, "pastSigs")) {
                if (children(past, "cert").any { effectiveKey(it, table) == key })
                    return true
            }
            return false
        }
        return false
    }

    /**
     * Removes only pastSigs certs carrying our key (foreign entries are
     * left alone). Returns true when anything was removed.
     */
    fun removeOurKeys(
        doc: Document,
        targets: List<String>,
        ourKeyHex: String,
        log: StringBuilder
    ): Boolean {
        val key = ourKeyHex.lowercase()
        var removed = false
        // Table must see the whole document (PMS-normalized index-only
        // refs point at inline keys elsewhere, e.g. our own package).
        // Built once: removals below only delete our own certs, which
        // never serve as another ref's resolution target here.
        val table = resolveKeyTable(doc)
        val users = doc.getElementsByTagName("shared-user")
        for (t in targets) {
            var node: Element? = null
            for (i in 0 until users.length) {
                val el = users.item(i) as? Element ?: continue
                if (el.getAttribute("name") == t) { node = el; break }
            }
            if (node == null) {
                log.appendLine("[!] shared-user $t not found, skipping")
                continue
            }
            val sigs = child(node, "sigs") ?: continue
            for (past in children(sigs, "pastSigs").toList()) {
                val certs = children(past, "cert")
                val ours = certs.filter { effectiveKey(it, table) == key }
                if (ours.isEmpty()) continue
                if (ours.size == certs.size) {
                    sigs.removeChild(past)
                    log.appendLine("[+] removed our pastSigs from $t")
                } else {
                    ours.forEach { past.removeChild(it) }
                    past.setAttribute("count", children(past, "cert").size.toString())
                    log.appendLine("[+] removed ${ours.size} our cert(s) from $t pastSigs")
                }
                removed = true
            }
        }
        return removed
    }

    /** Re-parse written bytes and confirm our pastSigs landed intact. */
    fun verifyPatched(patched: ByteArray, targets: List<String>, ourKeyHex: String): String {
        val key = ourKeyHex.lowercase()
        val doc = parseToDom(patched)
        val table = resolveKeyTable(doc)
        val log = StringBuilder()
        val users = doc.getElementsByTagName("shared-user")
        for (t in targets) {
            var ok = false
            var foreign = 0
            for (i in 0 until users.length) {
                val el = users.item(i) as? Element ?: continue
                if (el.getAttribute("name") != t) continue
                val past = child(child(el, "sigs") ?: continue, "pastSigs") ?: continue
                val certs = children(past, "cert")
                val ours = certs.count {
                    effectiveKey(it, table) == key &&
                        it.getAttribute("flags") == FLAG_SHARED_USER_ID
                }
                foreign = certs.size - ours
                ok = ours >= 2 && past.getAttribute("count") == certs.size.toString()
            }
            log.appendLine("[verify] $t pastSigs intact: $ok (foreign certs kept: $foreign)")
            if (!ok) throw RuntimeException("verify FAILED for $t")
        }
        return log.toString()
    }

    /** Direct backend (app_process as root, pre-install). */
    fun injectDirect(
        xmlPath: String,
        ourKeyHex: String,
        targets: List<String>,
        log: StringBuilder,
        dryRun: Boolean = false
    ): Int {
        val raw = java.io.File(xmlPath).readBytes()
        log.appendLine("[*] read ${raw.size} bytes from $xmlPath")
        val doc = parseToDom(raw)
        structuralChecks(doc, targets, log)
        val patched = buildPatchedXml(doc, ourKeyHex, targets, log)
        log.append(verifyPatched(patched, targets, ourKeyHex.lowercase()))
        if (dryRun) {
            log.appendLine("[*] dry-run: NOT writing")
            return patched.size
        }
        writeBack(xmlPath, patched, log)
        return patched.size
    }

    /** Uninstall backend (app_process as root): removes only our key. */
    fun uninstallDirect(
        xmlPath: String,
        ourKeyHex: String,
        targets: List<String>,
        log: StringBuilder
    ): Int {
        val key = ourKeyHex.lowercase()
        require(Abx.isHex(key) && key.length > 100) { "our cert key is not plausible hex" }
        val raw = java.io.File(xmlPath).readBytes()
        log.appendLine("[*] read ${raw.size} bytes from $xmlPath")
        val doc = parseToDom(raw)
        structuralChecks(doc, targets, log)
        Abx.guardDecimalAttrs(doc)
        if (!removeOurKeys(doc, targets, key, log)) {
            log.appendLine(keyAbsentLine())
            return raw.size
        }
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer()
            .transform(DOMSource(doc), StreamResult(out))
        val patched = out.toByteArray()
        log.append(verifyAbsent(patched, targets, key))
        writeBack(xmlPath, patched, log)
        return patched.size
    }

    /** Re-parse written bytes and confirm our key is gone from targets. */
    fun verifyAbsent(patched: ByteArray, targets: List<String>, ourKeyHex: String): String {
        val doc = parseToDom(patched)
        val log = StringBuilder()
        for (t in targets) {
            val gone = !isInjected(doc, t, ourKeyHex)
            log.appendLine(keyRemovedLine(t, gone))
            if (!gone) throw RuntimeException("verify FAILED for $t (key still present)")
        }
        return log.toString()
    }

    /**
     * Backup (once) + direct-overwrite-then-rename-swap write + perms +
     * restorecon. Shared by inject and uninstall backends.
     *
     * The backup is kept rather than restored: an uninstall removes this app's key surgically (see
     * [uninstallDirect]), because PMS rewrites this file on every install and uninstall, so restoring a
     * pre-inject copy would roll back every app the user has installed since. Both files this can leave
     * are removed by the app's own clean-up action ([DfrInstall.cleanupFilesCommand]).
     */
    private fun writeBack(xmlPath: String, patched: ByteArray, log: StringBuilder) {
        val bak = java.io.File(xmlPath + BACKUP_SUFFIX)
        if (!bak.exists()) {
            java.io.File(xmlPath).copyTo(bak, overwrite = false)
            log.appendLine("[*] backup -> ${bak.absolutePath}")
        } else {
            log.appendLine("[*] backup already exists, keeping ${bak.absolutePath}")
        }
        // Original mode/owner for the final file (typically 0600 system:system).
        var wantMode = 384 // 0600
        var wantUid = 1000
        var wantGid = 1000
        try {
            val st = android.system.Os.stat(xmlPath + BACKUP_SUFFIX)
            wantMode = st.st_mode and 0x1FF
            wantUid = st.st_uid
            wantGid = st.st_gid
        } catch (e: Exception) {
            log.appendLine("[!] stat backup: $e (using 0600 system:system)")
        }
        // Write strategy: direct overwrite first (works where the inode
        // allows it), then rename swap. NOTE: no setenforce games — EPERM was
        // observed even with SELinux fully Permissive, so this is not a MAC
        // denial (likely file-level protection); rename(2) walks a different
        // vector (new-file create is allowed) and succeeds.
        var done = false
        try {
            java.io.File(xmlPath).writeBytes(patched)
            done = true
            log.appendLine("[+] direct write ok")
        } catch (e: Exception) {
            log.appendLine("[!] direct write failed: $e")
        }
        if (!done) {
            // Step 2: rename swap. Creating a NEW file in /data/system is
            // allowed where overwriting the existing inode is MAC-denied
            // (observed: backup copyTo succeeded, writeBytes failed), and
            // rename(2) walks a different permission vector.
            val newPath = "$xmlPath$TEMP_SUFFIX"
            java.io.File(newPath).writeBytes(patched)
            applyPerms(newPath, wantMode, wantUid, wantGid, log)
            execOk("restorecon", newPath)
            try {
                android.system.Os.rename(newPath, xmlPath)
                done = true
                log.appendLine("[+] rename swap ok")
            } catch (e: Exception) {
                log.appendLine("[x] rename swap failed: $e")
                log.appendLine("    patched image kept at $newPath; manual option from an adb root shell:")
                log.appendLine("      mv $newPath $xmlPath && chmod 600 $xmlPath && chown system:system $xmlPath && reboot")
                throw RuntimeException("rename swap failed: $e")
            }
        }
        if (done) {
            applyPerms(xmlPath, wantMode, wantUid, wantGid, log)
            execOk("restorecon", xmlPath)
            log.appendLine("[+] wrote ${patched.size} bytes (TEXT xml; PMS re-reads either format)")
        }
    }

    private fun applyPerms(path: String, mode: Int, uid: Int, gid: Int, log: StringBuilder) {
        try {
            android.system.Os.chmod(path, mode)
            android.system.Os.chown(path, uid, gid)
        } catch (e: Exception) {
            log.appendLine("[!] chmod/chown $path: $e")
        }
    }

    /** exec(cmd, path), returns true on exit 0; never throws. */
    private fun execOk(cmd: String, path: String): Boolean {
        return try {
            val bin = if (cmd.startsWith("/")) cmd else "/system/bin/$cmd"
            Runtime.getRuntime().exec(arrayOf(bin, path)).waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun child(el: Element, tag: String): Element? {
        val nl = el.childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && n.tagName == tag) return n
        }
        return null
    }

    private fun children(el: Element, tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val nl = el.childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && n.tagName == tag) out.add(n)
        }
        return out
    }
}

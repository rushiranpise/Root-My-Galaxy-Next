/*
 * Ported into Root My Galaxy from DFReroot (https://github.com/polygraphene/DFReroot), installer
 * module. DFReroot carries no license; THIRD-PARTY.md at the repository root records what was taken,
 * from where, and why. Kept as close to the original as it can be, so a fix upstream stays
 * comparable line by line - the package name below and this header are the only additions.
 */
package dev.busung.s25uroot.dfr

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ABX (Android Binary XML) support for packages.xml on Android 13+.
 *
 * packages.xml is stored as ABX (magic `ABX\0`), so JAXP cannot read it.
 * We call the framework's own format-detecting entry point —
 * `android.util.Xml.resolvePullParser(InputStream)` — via reflection.
 * That method is `@hide` (absent from android.jar, hence reflection), but it
 * is only ever invoked under app_process-as-root (InjectMain), i.e. a
 * standalone dalvikvm process outside zygote's hidden-API enforcement.
 * Everything after obtaining the parser uses only public
 * org.xmlpull.v1.XmlPullParser methods. MainActivity never touches this
 * object directly; its Inject button shells out to InjectMain via su.
 *
 * Attribute copying goes through the STRING layer (getAttributeValue), which
 * is lossless for PMS's own readers with two handled exceptions:
 *  - cert `key=` is surfaced as lowercase hex (getValueBytesHex path) and PMS
 *    reads it back with hexStringToBytes, so our injected key MUST be hex too
 *    (see [toHex]; base64 is rejected).
 *  - INT_HEX/LONG_HEX wire values surface as hex strings which PMS's decimal
 *    parseInt would reject; [guardDecimalAttrs] aborts loudly if any
 *    PMS-canonical int attribute is non-decimal instead of silently corrupting.
 */
object Abx {
    private val MAGIC = byteArrayOf(0x41, 0x42, 0x58, 0x00)

    /** PMS-canonical integer attributes: always decimal in real files. */
    private val DECIMAL_ATTRS = setOf(
        "count", "index", "userId", "flags", "schemeVersion",
        "versionCode", "targetSdk", "minSdk"
    )

    fun isAbx(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        return MAGIC.indices.all { bytes[it] == MAGIC[it] }
    }

    /**
     * Framework's format-detecting parser (ABX or text). One reflective call;
     * the returned instance is used through the public interface only.
     */
    fun resolvePullParser(input: InputStream): XmlPullParser {
        val xmlClass = Class.forName("android.util.Xml")
        try {
            val m = xmlClass.getMethod("resolvePullParser", InputStream::class.java)
            return m.invoke(null, input) as XmlPullParser
        } catch (e: NoSuchMethodException) {
            // Very old framework without resolve*: binary-only fallback.
            val m = xmlClass.getMethod("newBinaryPullParser")
            val p = m.invoke(null) as XmlPullParser
            p.setInput(input, "UTF-8")
            return p
        }
    }

    /** Typed probe for diagnostics (dump mode). Null = not applicable/failed. */
    fun probeInt(p: XmlPullParser, index: Int): Int? {
        return try {
            p.javaClass.getMethod("getAttributeInt", Int::class.javaPrimitiveType)
                .invoke(p, index) as Int
        } catch (_: Exception) {
            null
        }
    }

    /** Typed probe for diagnostics (dump mode). Null = not bytes/failed. */
    fun probeBytesHex(p: XmlPullParser, index: Int): ByteArray? {
        return try {
            p.javaClass.getMethod("getAttributeBytesHex", Int::class.javaPrimitiveType)
                .invoke(p, index) as ByteArray?
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pull-parse (ABX or text via [resolvePullParser]) into a DOM Document.
     * Throws on: unknown tokens, non-whitespace text, unbalanced tags,
     * missing/multiple roots.
     */
    fun parseToDom(bytes: ByteArray): Document {
        val parser = resolvePullParser(ByteArrayInputStream(bytes))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val stack = ArrayDeque<Element>()
        var event = parser.eventType
        while (true) {
            when (event) {
                XmlPullParser.START_DOCUMENT -> { /* no-op */ }
                XmlPullParser.START_TAG -> {
                    val el = doc.createElement(parser.name ?: throw bad("null tag name"))
                    for (i in 0 until parser.attributeCount) {
                        val n = parser.getAttributeName(i)
                        // Namespace unsupported by ABX writer; JAXP text keeps plain names.
                        el.setAttribute(n, parser.getAttributeValue(i) ?: "")
                    }
                    if (stack.isEmpty()) {
                        doc.appendChild(el)
                    } else {
                        stack.last().appendChild(el)
                    }
                    stack.addLast(el)
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text ?: ""
                    if (t.isNotBlank()) throw bad("unexpected non-whitespace text: ${t.take(80)}")
                }
                XmlPullParser.END_TAG -> {
                    if (stack.isEmpty()) throw bad("stray END_TAG ${parser.name}")
                    val el = stack.removeLast()
                    if (el.tagName != parser.name) {
                        throw bad("unbalanced tags: open=${el.tagName} close=${parser.name}")
                    }
                }
                XmlPullParser.END_DOCUMENT -> break
                else -> throw bad("unexpected token $event")
            }
            event = parser.next()
        }
        if (doc.documentElement == null) throw bad("no root element")
        if (stack.isNotEmpty()) throw bad("unclosed tags remain")
        doc.documentElement.normalize()
        return doc
    }

    /**
     * Abort if any PMS-canonical int attribute anywhere in [doc] is not a
     * plain decimal integer. Such values would surface from INT_HEX/LONG_HEX
     * wire types whose hex string form PMS's decimal parseInt would reject on
     * re-read — writing them back as text would silently corrupt the file.
     */
    fun guardDecimalAttrs(doc: Document) {
        val all = doc.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val el = all.item(i) as? Element ?: continue
            val attrs = el.attributes ?: continue
            for (j in 0 until attrs.length) {
                val a = attrs.item(j)
                if (a.nodeName in DECIMAL_ATTRS && !a.nodeValue.matches(Regex("-?\\d+"))) {
                    throw bad(
                        "attribute ${el.tagName}@${a.nodeName}='${a.nodeValue}' is not decimal; " +
                            "refusing to rewrite (possible INT_HEX wire type)"
                    )
                }
            }
        }
    }

    /**
     * Read-only structural summary for `--dump`. Never writes. Used to confirm
     * the parser sees a sane packages.xml (hundreds of packages, expected
     * shared-users) before any mutation is attempted.
     */
    fun summarize(bytes: ByteArray): String {
        val s = StringBuilder()
        s.appendLine("[*] size=${bytes.size} format=${if (isAbx(bytes)) "ABX" else "TEXT?"}")
        val parser = resolvePullParser(ByteArrayInputStream(bytes))
        var packages = 0
        var permGroups = 0
        val sharedUsers = mutableListOf<String>()
        var firstPkgs = mutableListOf<String>()
        var event = parser.eventType
        while (true) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "package" -> {
                        packages++
                        if (firstPkgs.size < 5) {
                            firstPkgs.add(parser.getAttributeValue(null, "name") ?: "?")
                        }
                    }
                    "shared-user" -> sharedUsers.add(
                        "${parser.getAttributeValue(null, "name")}" +
                            "/uid=${parser.getAttributeValue(null, "userId")}"
                    )
                    "permission" -> permGroups++
                }
                XmlPullParser.END_DOCUMENT -> break
            }
            event = parser.next()
        }
        s.appendLine("[*] <package> count=$packages")
        s.appendLine("[*] first packages: $firstPkgs")
        s.appendLine("[*] shared-users: $sharedUsers")
        s.appendLine("[*] <permission> count=$permGroups")
        if (packages < 20) s.appendLine("[!] SUSPICIOUSLY FEW packages; abort any write")
        if (sharedUsers.none { it.startsWith("android.uid.system/") }) {
            s.appendLine("[!] android.uid.system MISSING; abort any write")
        }
        return s.toString()
    }

    fun toHex(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return out.toString()
    }

    fun isHex(s: String): Boolean {
        if (s.isEmpty() || s.length % 2 != 0) return false
        return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun bad(msg: String) = RuntimeException("ABX: $msg")
}

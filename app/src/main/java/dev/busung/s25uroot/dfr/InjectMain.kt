/*
 * Ported into Root My Galaxy from DFReroot (https://github.com/polygraphene/DFReroot), installer
 * module. DFReroot carries no license; THIRD-PARTY.md at the repository root records what was taken,
 * from where, and why. Kept as close to the original as it can be, so a fix upstream stays
 * comparable line by line.
 *
 * The two changes: the package name, and the default package whose key is looked up when no --apk
 * and no --keyhex is given, which is this app rather than theirs.
 */
package dev.busung.s25uroot.dfr

import dev.busung.s25uroot.BuildConfig

/**
 * app_process entry point. Runs as root.
 *
 * The app reaches it through its own root shell, with **this app's installed APK as the classpath** -
 * the ported classes are compiled into it, so nothing is staged to the device first. Manual example:
 *   su -c 'CLASSPATH=<this app's base.apk> app_process /system/bin \
 *     --nice-name=rmg_inject dev.busung.s25uroot.dfr.InjectMain \
 *     --apk /sdcard/Download/picked.apk [--xml /data/system/packages.xml] \
 *     [--targets android.uid.system] [--dry-run|--dump]'
 *
 * The signing key comes from --keyhex (GUI path: MainActivity reads it via
 * PackageManager, which handles v1/v2/v3 uniformly), from --apk (manual
 * path: this entry builds the System Context via ActivityThread.systemMain()
 * and reads the APK file through PackageManager — see [SysKey]), or from
 * --pkg (repair path: key of the already-installed package).
 *
 * Modes: --dump (parse + summarize only, zero writes; run FIRST),
 * --check (report per-target injected=true/false, zero writes),
 * --dry-run (parse + transform + verify, no write),
 * --uninstall (remove only our key, zero-op when absent),
 * default (full inject; FAILS when our key is already present).
 *
 * (see above for the full mode list).
 */
object InjectMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var apk = ""
        var keyHexArg = ""
        var xml = PackagesXml.PACKAGES_XML
        // Read from the build rather than typed: an install id spelled out in a source file still
        // compiles after the next rename and then points at an app that is not installed. See
        // InstallIdentityTest, which holds every Kotlin file to this.
        var pkg = BuildConfig.APPLICATION_ID
        var targets = listOf("android.uid.system")
        var dryRun = false
        var dump = false
        var check = false
        var uninstall = false
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--apk" -> apk = args.getOrElse(i + 1) { "" }.also { i += 2 }
                "--keyhex" -> keyHexArg = args.getOrElse(i + 1) { "" }.also { i += 2 }
                "--xml" -> xml = args.getOrElse(i + 1) { xml }.also { i += 2 }
                "--pkg" -> pkg = args.getOrElse(i + 1) { pkg }.also { i += 2 }
                "--targets" -> targets = args.getOrElse(i + 1) { "" }
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }.also { i += 2 }
                "--dry-run" -> { dryRun = true; i++ }
                "--dump" -> { dump = true; i++ }
                "--check" -> { check = true; i++ }
                "--uninstall" -> { uninstall = true; i++ }
                else -> i++
            }
        }
        if (!dump && keyHexArg.isEmpty() && apk.isEmpty() && pkg.isEmpty()) {
            System.out.println("usage: InjectMain [--keyhex <hex> | --apk <apk> | --pkg <installed>] [--xml ...] [--targets a,b] [--dry-run|--dump|--check|--uninstall]")
            kotlin.system.exitProcess(2)
        }
        val log = StringBuilder()
        try {
            log.appendLine("[*] uid=${android.os.Process.myUid()} apk=$apk")
            val raw = java.io.File(xml).readBytes()
            if (dump) {
                log.append(Abx.summarize(raw))
                System.out.println(log.toString())
                return
            }
            val keyHex = if (keyHexArg.isNotEmpty()) {
                val k = keyHexArg.trim().lowercase()
                require(Abx.isHex(k) && k.length > 100) { "--keyhex is not plausible hex" }
                log.appendLine("[+] our cert from --keyhex len=${k.length}")
                k
            } else if (apk.isNotEmpty()) {
                SysKey.keyHexFromApk(apk).also {
                    log.appendLine("[+] our cert from APK file $apk len=${it.length}")
                }
            } else {
                SysKey.keyHexInstalled(pkg).also {
                    log.appendLine("[+] our cert from installed $pkg len=${it.length}")
                }
            }
            if (check) {
                val doc = PackagesXml.parseToDom(raw)
                var all = true
                for (t in targets) {
                    val hit = PackagesXml.isInjected(doc, t, keyHex)
                    log.appendLine("[check] $t injected=$hit")
                    if (!hit) all = false
                }
                log.appendLine("[check] all_injected=$all")
                System.out.println(log.toString())
                return
            }
            if (uninstall) {
                PackagesXml.uninstallDirect(xml, keyHex, targets, log)
                val rc = Runtime.getRuntime().exec(arrayOf("/system/bin/restorecon", xml)).waitFor()
                log.appendLine("[*] restorecon rc=$rc")
                // Removal takes effect on the next framework start
                // (PMS re-reads packages.xml), like the inject path - but only when a removal actually
                // happened. A file that was already clean needs no restart, and this line is the last
                // thing the user reads before deciding whether to restart the phone.
                log.appendLine(
                    if (log.contains(PackagesXml.KEY_CHANGED)) {
                        "[+] DONE. our key removed; soft reboot to apply"
                    } else {
                        "[+] DONE. our key was not in the file; nothing to apply"
                    },
                )
                System.out.println(log.toString())
                return
            }
            PackagesXml.injectDirect(xml, keyHex, targets, log, dryRun)
            if (!dryRun) {
                val rc = Runtime.getRuntime().exec(arrayOf("/system/bin/restorecon", xml)).waitFor()
                log.appendLine("[*] restorecon rc=$rc")
                // PMS reads packages.xml only at boot (and on writeSettings),
                // so the DFReroot install MUST happen after a reboot, never before.
                // This is the last line a user reads at the end of an inject, so it names this project's
                // helper rather than the one this was ported from: the APK to install after the reboot is
                // the one the app carries in its assets.
                log.appendLine("[+] DONE. next: reboot, THEN install the RMG-NEXT helper APK")
            }
            System.out.println(log.toString())
        } catch (e: Exception) {
            System.out.println(log.toString())
            System.out.println("[x] FAILED: $e")
            e.printStackTrace(System.out)
            kotlin.system.exitProcess(1)
        }
    }
}

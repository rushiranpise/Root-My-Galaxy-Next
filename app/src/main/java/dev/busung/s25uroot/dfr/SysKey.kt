/*
 * Ported into Root My Galaxy from DFReroot (https://github.com/polygraphene/DFReroot), installer
 * module. DFReroot carries no license; THIRD-PARTY.md at the repository root records what was taken,
 * from where, and why. Kept as close to the original as it can be, so a fix upstream stays
 * comparable line by line - the package name below and this header are the only additions.
 */
package dev.busung.s25uroot.dfr

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper

/**
 * Signing-key lookup for app_process-as-root ([InjectMain]), where there is
 * no app Context. Builds the framework System Context in-process and reads
 * signatures through PackageManager — v1/v2/v3 agnostic, no hand parsing,
 * no cert.b64 sidecar, no v1-only PKCS#7 fallback.
 *
 * Context creation mirrors what system_server itself does at startup:
 * reuse the already-bound application when present, otherwise create the
 * System Context via `ActivityThread.systemMain()`. All three methods used
 * (`currentApplication`, `systemMain`, `getSystemContext`) are public, and
 * `getPackageArchiveInfo`/`getPackageInfo` need no special permission for
 * this read-only use.
 *
 * (In-app callers that already have a Context use [SigKey] instead and get
 * identical results for the same APK bytes.)
 */
object SysKey {
    /** Framework System Context for this app_process. */
    fun systemContext(): Context {
        Looper.prepare()
        val at = Class.forName("android.app.ActivityThread")
        try {
            val current = at.getMethod("currentApplication").invoke(null) as? Context
            if (current != null) return current
        } catch (_: Exception) {
            // No bound application (bare app_process): fall through.
        }
        val thread = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    /** Full-cert hex of the signer of the APK file at [apkPath]. */
    fun keyHexFromApk(apkPath: String): String {
        require(java.io.File(apkPath).isFile) { "apk not found: $apkPath" }
        return SigKey.fromApk(systemContext().packageManager, apkPath)
    }

    /**
     * Full-cert hex of an already-installed package (repair case: re-inject
     * after DFReroot was installed once). Same key as the file path when
     * both APKs share one signing key.
     */
    fun keyHexInstalled(pkg: String): String {
        val pm = systemContext().packageManager
        val pi = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            throw RuntimeException("package not installed: $pkg")
        } ?: throw RuntimeException("no package info for $pkg")
        val signers = pi.signingInfo?.apkContentsSigners
            ?: throw RuntimeException("no apkContentsSigners for $pkg")
        if (signers.isEmpty()) throw RuntimeException("empty signers for $pkg")
        val hex = Abx.toHex(signers[0].toByteArray())
        require(Abx.isHex(hex) && hex.length > 100) { "implausible cert for $pkg" }
        return hex
    }
}

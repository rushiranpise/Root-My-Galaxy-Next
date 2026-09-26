/*
 * Ported into Root My Galaxy from DFReroot (https://github.com/polygraphene/DFReroot), installer
 * module. DFReroot carries no license; THIRD-PARTY.md at the repository root records what was taken,
 * from where, and why. Kept as close to the original as it can be, so a fix upstream stays
 * comparable line by line - the package name below and this header are the only additions.
 */
package dev.busung.s25uroot.dfr

import android.content.pm.PackageManager

object SigKey {
    fun fromApk(pm: PackageManager, apkPath: String): String {
        // minSdk 32, so SigningInfo (API 28+) is always available.
        val pi = pm.getPackageArchiveInfo(
            apkPath, PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: throw RuntimeException("getPackageArchiveInfo returned null for $apkPath")

        val si = pi.signingInfo
            ?: throw RuntimeException("no signingInfo in $apkPath")

        // Current signer only (NOT the rotation history): this is the
        // cert PMS will see when the APK is installed.
        val signers = si.apkContentsSigners
            ?: throw RuntimeException("no apkContentsSigners in $apkPath")

        if (signers.isEmpty()) throw RuntimeException("empty signers in $apkPath")

        val der: ByteArray = signers[0].toByteArray()
        val hex = Abx.toHex(der)
        require(Abx.isHex(hex) && hex.length > 100) { "implausible cert bytes in $apkPath" }
        return hex
    }
}

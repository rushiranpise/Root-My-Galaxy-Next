package dev.busung.s25uroot

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val apkUrl: String?,
    val releaseUrl: String,
)

const val ROOT_MY_GALAXY_URL = "https://github.com/rushiranpise/Root-My-Galaxy"

object AppUpdater {

    // This fork's own repository, and the one thing here that has to be right: the update check
    // downloads whatever APK this answers with, and the other install's is signed with a key this app
    // does not hold, so an update offered from there could never be installed.
    private const val GITHUB_API = "https://api.github.com/repos/rushiranpise/Root-My-Galaxy"
    private const val RELEASES_PAGE = "$ROOT_MY_GALAXY_URL/releases/latest"

    suspend fun fetchLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL("$GITHUB_API/releases/latest").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "RootMyGalaxyNext/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val body = connection.inputStream.bufferedReader().use { it.readText() }

                val json = JSONObject(body)
                val tag = json.optString("tag_name").trim().removePrefix("v")
                if (tag.isBlank()) return@withContext null
                var apkUrl: String? = null
                json.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url").ifEmpty { null }
                            break
                        }
                    }
                }
                UpdateInfo(
                    versionName = tag,
                    apkUrl = apkUrl,
                    releaseUrl = json.optString("html_url").ifEmpty { RELEASES_PAGE },
                )
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Whether [latestVersion], a release tag, is newer than the installed build.
     *
     * Builds carry a `+ci.<run>.<sha>` or `+local.<sha>` suffix so two installs of the same version
     * can be told apart, which means a plain string comparison would report the release already
     * installed as an update and offer it forever. The comparison is therefore on the dotted version
     * numbers, and it also gets `0.2.10` versus `0.2.9` right, which string order does not.
     */
    fun isUpdateAvailable(latestVersion: String, currentVersion: String): Boolean {
        if (latestVersion.isEmpty()) return false
        val latest = versionBase(latestVersion)
        val current = versionBase(currentVersion)
        // A tag that is not a dotted version at all cannot be ordered; fall back to "different".
        if (latest == null || current == null) return latestVersion != currentVersion
        return compareVersions(latest, current) > 0
    }

    /** `v0.2.65+ci.42.ab12cd3` becomes `0.2.65`; null when there is no dotted number in it. */
    internal fun versionBase(version: String): String? {
        val stripped = version.trim()
            .removePrefix("v")
            .substringBefore('+')
            .substringBefore('-')
        return stripped.takeIf { candidate ->
            candidate.isNotEmpty() && candidate.all { it.isDigit() || it == '.' }
        }
    }

    /** Component-wise, so a missing part counts as zero and `0.3` beats `0.2.9`. */
    internal fun compareVersions(latest: String, current: String): Int {
        val latestParts = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val difference =
                (latestParts.getOrNull(index) ?: 0) - (currentParts.getOrNull(index) ?: 0)
            if (difference != 0) return difference
        }
        return 0
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "update.apk")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "RootMyGalaxyNext/${BuildConfig.VERSION_NAME}")
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val total = connection.contentLength
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (target.length() == 0L) return@withContext null
                target
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    fun installApk(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openReleasesPage(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)))
    }
}

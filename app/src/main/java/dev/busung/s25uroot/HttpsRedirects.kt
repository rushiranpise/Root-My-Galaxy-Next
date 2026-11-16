package dev.busung.s25uroot

import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens GET connections without letting a redirect downgrade them.
 *
 * [HttpURLConnection]'s own redirect handling follows a Location header wherever it points, plain http
 * included, so every hop is resolved here instead: the initial URL and every redirect target must be https
 * before a request is made to it, and the chain is capped so a redirect loop cannot spin forever.
 *
 * Adapted from the S24 fork's hardening for Next's multi-source payload fetch paths.
 */
internal object HttpsRedirects {
    private const val MAX_REDIRECTS = 5

    /** Redirect codes followed here: only 301/302/303/307/308; 300/304 are not. */
    private val REDIRECT_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM, // 301
        HttpURLConnection.HTTP_MOVED_TEMP, // 302
        HttpURLConnection.HTTP_SEE_OTHER, // 303
        307, // HTTP_TEMPORARY_REDIRECT
        308, // HTTP_PERMANENT_REDIRECT
    )

    /**
     * Returns a connected [HttpURLConnection] for [url] with every redirect already followed. The response
     * code of the final connection is left unchecked so each caller keeps its own treatment of non-OK
     * responses.
     */
    fun open(
        url: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        userAgent: String,
        headers: Map<String, String> = emptyMap(),
        connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): HttpURLConnection {
        var connection = connect(
            requireHttps(URL(url)),
            connectTimeoutMillis,
            readTimeoutMillis,
            userAgent,
            headers,
            connectionFactory,
        )
        try {
            var redirects = 0
            while (true) {
                val code = connection.responseCode
                if (code !in REDIRECT_CODES) return connection
                require(redirects < MAX_REDIRECTS) {
                    "Refusing to follow more than $MAX_REDIRECTS redirects while opening $url"
                }
                val location = connection.getHeaderField("Location")
                require(!location.isNullOrBlank()) {
                    "Redirect from ${connection.url} (HTTP $code) has no Location header"
                }
                val next = resolveHttps(connection.url, location)
                connection.disconnect()
                connection = connect(
                    next,
                    connectTimeoutMillis,
                    readTimeoutMillis,
                    userAgent,
                    headers,
                    connectionFactory,
                )
                redirects++
            }
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }

    /**
     * Resolves [location] the way a browser would - relative, absolute or protocol-relative - and refuses
     * anything that is not https.
     */
    internal fun resolveHttps(base: URL, location: String): URL {
        val resolved = URL(base, location)
        require(resolved.protocol == "https") { "Refusing non-HTTPS redirect to $resolved" }
        return resolved
    }

    /** Fails before any connection is opened, so an http URL is never upgraded around. */
    private fun requireHttps(url: URL): URL {
        require(url.protocol == "https") { "Refusing non-HTTPS URL $url" }
        return url
    }

    private fun connect(
        url: URL,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        userAgent: String,
        headers: Map<String, String>,
        connectionFactory: (URL) -> HttpURLConnection,
    ): HttpURLConnection = connectionFactory(url).apply {
        connectTimeout = connectTimeoutMillis
        readTimeout = readTimeoutMillis
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", userAgent)
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
    }
}

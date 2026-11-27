package com.ayham.navidrome

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The server answered with a Subsonic error. [code] is the Subsonic error code (0 if missing), e.g.
 * 70 "not found" (retrying won't help) or 40 "wrong username or password" (it will, after a new login).
 * HTTP and network errors stay plain IOExceptions.
 */
class SubsonicException(message: String, val code: Int = 0) : IOException(message) {
    /** Login problems (40-44, e.g. a changed password) or no code at all: the request itself may be fine, so keep it. */
    val isAuthError: Boolean get() = code == 0 || code in 40..44
}

data class ServerInfo(val type: String, val serverVersion: String, val apiVersion: String) {
    val label: String get() = listOf(type, serverVersion).filter { it.isNotBlank() }.joinToString(" ")
}

data class Artist(val id: String, val name: String, val coverArt: String?)

data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String,
    val coverArt: String?,
    val year: Int,
    /** As reported in the album list; used to check that the song listing is complete. */
    val songCount: Int = 0,
)

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val artistId: String,
    val albumArtist: String,
    val track: Int,
    val disc: Int,
    val year: Int,
    val genre: String,
    val durationMs: Long,
    val size: Long,
    val suffix: String,
    val contentType: String,
    val bitRate: Int,
    val bitDepth: Int,
    val coverArt: String?,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val composer: String = "",
) {
    /** Changes when anything Poweramp shows changes; used to bump LAST_MODIFIED. */
    val metaHash: String
        get() = listOf(title, artist, album, albumArtist, track, disc, year, genre, durationMs, size, suffix,
            coverArt, bitRate, bitDepth, sampleRate, channelCount, composer)
            .joinToString("\u0001").hashCode().toString(16)
}

data class Playlist(val id: String, val name: String, val changed: String, val songIds: List<String>)

data class Radio(val id: String, val name: String, val url: String)

data class Lyrics(val synced: String?, val plain: String?) {
    val isEmpty: Boolean get() = synced.isNullOrBlank() && plain.isNullOrBlank()
}

/**
 * Minimal Subsonic / OpenSubsonic client with token auth (u, t = md5(password + s), s).
 * Every call is blocking; run it off the main thread.
 */
class SubsonicClient(
    serverUrl: String,
    private val user: String,
    private val salt: String,
    private val token: String,
    trustAll: Boolean,
    /** SHA-256 of the self-signed certificate trusted for this server; blank = not pinned yet. */
    certPin: String = "",
    /** Called with the fingerprint when a blank [certPin] gets pinned on the first connection. */
    onPin: (String) -> Unit = {},
) {
    private val base: HttpUrl = normalizeUrl(serverUrl).toHttpUrl()

    val http: OkHttpClient = httpClient(trustAll, certPin, onPin)

    fun url(method: String, vararg params: Pair<String, Any>): HttpUrl =
        base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(method)
            .addQueryParameter("u", user)
            .addQueryParameter("t", token)
            .addQueryParameter("s", salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v.toString()) } }
            .build()

    private fun call(method: String, vararg params: Pair<String, Any>): JSONObject {
        val req = Request.Builder().url(url(method, *params)).build()
        http.newCall(req).execute().use { resp ->
            // A proxy's 502 while Navidrome restarts is temporary: not a SubsonicException.
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $method")
            val body = resp.body?.string() ?: throw IOException("Empty response for $method")
            return checkOk(JSONObject(body).getJSONObject("subsonic-response"), method)
        }
    }

    private fun checkOk(root: JSONObject, method: String): JSONObject {
        if (root.optString("status") != "ok") {
            val err = root.optJSONObject("error")
            throw SubsonicException(err?.optString("message") ?: "Server error in $method", err?.optInt("code") ?: 0)
        }
        return root
    }

    /**
     * Subsonic reports errors (song gone, bad token) as HTTP 200 with a JSON or XML body. For audio
     * that body must never be taken for the file: throw instead and close the response.
     */
    private fun audio(resp: Response, what: String): Response {
        val type = resp.header("Content-Type").orEmpty()
        if (resp.isSuccessful && (type.contains("json") || type.contains("xml"))) {
            val body = resp.use { it.body?.string().orEmpty() }
            runCatching { JSONObject(body).getJSONObject("subsonic-response") }.getOrNull()?.let { checkOk(it, what) }
            throw SubsonicException("Server sent $type instead of audio for $what")
        }
        return resp
    }

    fun ping(): ServerInfo {
        val r = call("ping")
        return ServerInfo(
            type = r.optString("type", "subsonic"),
            serverVersion = r.optString("serverVersion", ""),
            apiVersion = r.optString("version", ""),
        )
    }

    fun artists(): List<Artist> {
        val out = ArrayList<Artist>()
        val indexes = call("getArtists").optJSONObject("artists")?.optJSONArray("index") ?: return out
        for (i in 0 until indexes.length()) {
            val list = indexes.getJSONObject(i).optJSONArray("artist") ?: continue
            for (j in 0 until list.length()) {
                val a = list.getJSONObject(j)
                out += Artist(a.getString("id"), a.optString("name"), a.coverArtId())
            }
        }
        return out
    }

    fun albums(): List<Album> {
        val out = ArrayList<Album>()
        var offset = 0
        while (true) {
            val page = call("getAlbumList2", "type" to "alphabeticalByName", "size" to PAGE, "offset" to offset)
                .optJSONObject("albumList2")?.optJSONArray("album") ?: break
            for (i in 0 until page.length()) {
                val a = page.getJSONObject(i)
                out += Album(
                    id = a.getString("id"),
                    name = a.optString("name", a.optString("album")),
                    artist = a.optString("artist"),
                    artistId = a.optString("artistId"),
                    coverArt = a.coverArtId(),
                    year = a.optInt("year"),
                    songCount = a.optInt("songCount"),
                )
            }
            if (page.length() < PAGE) break
            offset += PAGE
        }
        return out
    }

    /** Every song, via search3 with an empty query (Navidrome returns the whole library, paged). */
    fun songs(onProgress: (Int) -> Unit = {}): List<Song> {
        val out = ArrayList<Song>()
        var offset = 0
        while (true) {
            val page = call(
                "search3", "query" to "", "artistCount" to 0, "albumCount" to 0,
                "songCount" to PAGE, "songOffset" to offset,
            ).optJSONObject("searchResult3")?.optJSONArray("song") ?: break
            for (i in 0 until page.length()) out += parseSong(page.getJSONObject(i))
            onProgress(out.size)
            if (page.length() < PAGE) break
            offset += PAGE
        }
        return out
    }

    /**
     * Songs of one album. Used when search3 does not list the whole library (servers other than
     * Navidrome may return nothing for an empty query).
     */
    fun albumSongs(albumId: String): List<Song> {
        val list = call("getAlbum", "id" to albumId).optJSONObject("album")?.optJSONArray("song") ?: return emptyList()
        return (0 until list.length()).map { parseSong(list.getJSONObject(it)) }
    }

    internal fun parseSong(s: JSONObject) = Song(
        id = s.getString("id"),
        title = s.optString("title"),
        artist = s.optString("artist"),
        album = s.optString("album"),
        albumId = s.optString("albumId"),
        artistId = s.optString("artistId"),
        albumArtist = s.optString("displayAlbumArtist").ifBlank { s.optString("albumArtist") },
        track = s.optInt("track"),
        disc = s.optInt("discNumber"),
        year = s.optInt("year"),
        genre = s.optString("genre"),
        durationMs = s.optLong("duration") * 1000,
        size = s.optLong("size"),
        suffix = s.optString("suffix"),
        contentType = s.optString("contentType"),
        bitRate = s.optInt("bitRate"),
        bitDepth = s.optInt("bitDepth"),
        coverArt = s.coverArtId(),
        sampleRate = s.optInt("samplingRate"),
        channelCount = s.optInt("channelCount"),
        composer = s.optString("displayComposer"),
    )

    /** Every playlist with its song ids in order (one extra request per playlist). */
    fun playlists(): List<Playlist> {
        val list = call("getPlaylists").optJSONObject("playlists")?.optJSONArray("playlist") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val id = p.getString("id")
            val entries = call("getPlaylist", "id" to id).optJSONObject("playlist")?.optJSONArray("entry") ?: JSONArray()
            Playlist(
                id = id,
                name = p.optString("name"),
                changed = p.optString("changed"),
                songIds = (0 until entries.length()).map { entries.getJSONObject(it).getString("id") },
            )
        }
    }

    fun radios(): List<Radio> {
        val list = call("getInternetRadioStations").optJSONObject("internetRadioStations")
            ?.optJSONArray("internetRadioStation") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val r = list.getJSONObject(i)
            Radio(r.getString("id"), r.optString("name"), r.optString("streamUrl"))
        }
    }

    /** Navidrome favourite (heart). */
    fun setStarred(id: String, starred: Boolean) {
        call(if (starred) "star" else "unstar", "id" to id)
    }

    /** 1..5 stars, 0 removes the rating. */
    fun setRating(id: String, rating: Int) {
        call("setRating", "id" to id, "rating" to rating)
    }

    /**
     * [submission] false = "now playing" (shown live in Navidrome), true = a finished play (play count,
     * Last.fm / ListenBrainz forwarding). [time] is when the song started, in ms.
     */
    fun scrobble(id: String, time: Long, submission: Boolean) {
        call("scrobble", "id" to id, "time" to time, "submission" to submission)
    }

    fun coverArt(id: String, size: Int): ByteArray? {
        val req = Request.Builder().url(url("getCoverArt", "id" to id, "size" to size)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val type = resp.header("Content-Type") ?: ""
            if (!type.startsWith("image/")) return null
            return resp.body?.bytes()
        }
    }

    /** A smaller Opus copy made by Navidrome on the fly (for mobile data). Not seekable, size unknown. */
    fun streamTranscoded(id: String, maxBitRate: Int): Response {
        val req = Request.Builder()
            .url(url("stream", "id" to id, "format" to "opus", "maxBitRate" to maxBitRate))
            .build()
        return audio(http.newCall(req).execute(), "stream $id")
    }

    /** The original file (format=raw keeps sizes exact and seeking possible), optionally from [from] bytes. */
    fun stream(id: String, from: Long = 0): Response {
        val req = Request.Builder()
            .url(url("stream", "id" to id, "format" to "raw"))
            .apply { if (from > 0) header("Range", "bytes=$from-") }
            .build()
        return audio(http.newCall(req).execute(), "stream $id")
    }

    /** Synced lyrics as LRC when the server has them, else plain text. */
    fun lyrics(songId: String, artist: String, title: String): Lyrics {
        try {
            val list = call("getLyricsBySongId", "id" to songId)
                .optJSONObject("lyricsList")?.optJSONArray("structuredLyrics")
            if (list != null && list.length() > 0) {
                var synced: String? = null
                var plain: String? = null
                for (i in 0 until list.length()) {
                    val l = list.getJSONObject(i)
                    val lines = l.optJSONArray("line") ?: JSONArray()
                    if (l.optBoolean("synced") && synced == null) synced = toLrc(lines, l.optLong("offset"))
                    if (!l.optBoolean("synced") && plain == null) plain = toPlain(lines)
                }
                if (synced != null || plain != null) return Lyrics(synced, plain)
            }
        } catch (_: SubsonicException) {
            // Not an OpenSubsonic server: fall back to the classic endpoint below.
        }
        val text = call("getLyrics", "artist" to artist, "title" to title)
            .optJSONObject("lyrics")?.optString("value").orEmpty()
        return Lyrics(null, text.ifBlank { null })
    }

    private fun toLrc(lines: JSONArray, offsetMs: Long): String = buildString {
        for (i in 0 until lines.length()) {
            val l = lines.getJSONObject(i)
            val ms = (l.optLong("start") + offsetMs).coerceAtLeast(0)
            // Locale.ROOT: in e.g. Arabic the default locale writes Arabic-Indic digits, which LRC parsers can't read.
            append(String.format(Locale.ROOT, "[%02d:%02d.%02d]", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10))
            append(l.optString("value")).append('\n')
        }
    }

    private fun toPlain(lines: JSONArray): String =
        (0 until lines.length()).joinToString("\n") { lines.getJSONObject(it).optString("value") }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "PowerampNavidrome"
        private const val PAGE = 500

        fun hasScheme(url: String): Boolean =
            url.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }

        /** Without a scheme the address is taken as https, so the token never goes out unencrypted by accident. */
        fun normalizeUrl(url: String): String {
            var u = url.trim().trimEnd('/')
            if (!hasScheme(u)) u = "https://$u"
            return "$u/"
        }

        /**
         * Addresses plain http is acceptable for: private LAN ranges, loopback, Tailscale
         * (100.64.0.0/10, *.ts.net) and .local/.lan/.home names. Anything else is the open internet.
         */
        fun isLocalAddress(url: String): Boolean {
            val host = runCatching { normalizeUrl(url).toHttpUrl().host }.getOrNull()?.lowercase() ?: return false
            if (host == "localhost" || listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".ts.net").any { host.endsWith(it) }) {
                return true
            }
            // Literal IPs only, parsed by hand: no DNS lookup, safe on the main thread.
            if (host.contains(':')) { // IPv6: loopback, link-local fe80::/10, unique-local fc00::/7
                return host == "::1" || host.startsWith("fe8") || host.startsWith("fe9") ||
                    host.startsWith("fea") || host.startsWith("feb") || host.startsWith("fc") || host.startsWith("fd")
            }
            val b = host.split('.').map { it.toIntOrNull() ?: return false }
            if (b.size != 4 || b.any { it !in 0..255 }) return false
            return b[0] == 10 || b[0] == 127 ||
                (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254) ||
                (b[0] == 100 && b[1] in 64..127) // Tailscale / CGNAT 100.64.0.0/10
        }

        /** True when the address sends the token unencrypted over the internet. */
        fun isInsecureRemote(url: String): Boolean =
            url.trim().startsWith("http://", true) && !isLocalAddress(url)

        /** Returns (salt, token) for Subsonic token auth. */
        fun makeToken(password: String): Pair<String, String> {
            val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val salt = bytes.joinToString("") { "%02x".format(it) }
            val md5 = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray())
            return salt to md5.joinToString("") { "%02x".format(it) }
        }

        fun sha256(cert: java.security.cert.Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

        /**
         * With [trustAll] off: normal certificate checks. With it on ("Accept untrusted HTTPS
         * certificates", for self-signed home servers): certificates the system trusts pass as usual;
         * otherwise only the one certificate pinned on the first connection is accepted, so a different
         * certificate presented later (someone in the middle) is refused. A blank [pin] is filled from
         * the first connection and reported through [onPin].
         */
        fun httpClient(trustAll: Boolean, pin: String = "", onPin: (String) -> Unit = {}): OkHttpClient {
            val b = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
            if (trustAll) {
                val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(null as KeyStore?) }
                    .trustManagers.filterIsInstance<X509TrustManager>().first()
                val pinned = AtomicReference(pin)
                val tm = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                        system.checkClientTrusted(chain, authType)

                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        val fp = sha256(chain.first())
                        // Pinned on the first connection even if the system trusts it, so an untrusted
                        // certificate turning up later is refused rather than pinned.
                        if (pinned.compareAndSet("", fp)) onPin(fp)
                        try {
                            system.checkServerTrusted(chain, authType)
                            return
                        } catch (_: CertificateException) {
                            // Not trusted by the system: only the pinned certificate may pass.
                        }
                        if (fp != pinned.get()) throw CertificateException(
                            "The server's certificate changed. If you renewed it, turn " +
                                "\"Accept untrusted HTTPS certificates\" off and on again."
                        )
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
                }
                val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
                val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                b.sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier { host, session ->
                    // Self-signed certificates often don't name the address used (a LAN IP, say);
                    // the pinned fingerprint already identifies the server.
                    defaultVerifier.verify(host, session) ||
                        runCatching { sha256(session.peerCertificates.first()) == pinned.get() }.getOrDefault(false)
                }
            }
            return b.build()
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

/**
 * Navidrome adds a cache-busting suffix to cover ids ("mf-<id>_<hex>") that changes when the item's
 * annotation changes, e.g. after a play. Without it the id is stable, the server accepts it, and a
 * sync no longer counts played songs as changed or caches the same cover again under a new id.
 */
private fun JSONObject.coverArtId(): String? = optStringOrNull("coverArt")?.replace(COVER_SUFFIX, "")

private val COVER_SUFFIX = Regex("_[0-9a-f]{8,}$")

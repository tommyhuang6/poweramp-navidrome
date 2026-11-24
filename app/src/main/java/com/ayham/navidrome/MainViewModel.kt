package com.ayham.navidrome

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val configured: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val serverVersion: String = "",
    val trustAllCerts: Boolean = false,
    val lastSync: Long = 0,
    val counts: LibraryCounts = LibraryCounts(0, 0, 0, 0, 0, 0),
    val albums: List<AlbumSummary> = emptyList(),
    val removed: List<SongRow> = emptyList(),
    val allOffline: Boolean = false,
    val prefetch: Prefetch = Prefetch.NEXT,
    val cacheLimitMb: Long = 0,
    val matchLocalAlbums: Boolean = false,
    val albumSuffix: String = "",
    val scrobble: Boolean = true,
    val pendingScrobbles: Int = 0,
    val ratings: Boolean = true,
    val wifiOnlyDownloads: Boolean = true,
    val streamQuality: StreamQuality = StreamQuality.ORIGINAL,
    val lowerBitrate: Int = 128,
    val connecting: Boolean = false,
    val connectError: String? = null,
    val toast: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val s = App.services(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    val work = s.status

    init {
        // Library or cache changed (sync, finished download, clear), or a job reported progress.
        viewModelScope.launch { s.changes.collect { refresh() } }
        viewModelScope.launch { s.status.collect { refresh() } }
    }

    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val st = s.settings
                UiState(
                    configured = st.isConfigured,
                    serverUrl = st.serverUrl,
                    username = st.username,
                    serverVersion = st.serverVersion,
                    trustAllCerts = st.trustAllCerts,
                    lastSync = st.lastSync,
                    counts = s.db.counts(),
                    albums = s.db.albumSummaries(),
                    removed = s.db.removedSongs(),
                    allOffline = ("all" to "") in s.db.pins(),
                    prefetch = st.prefetch,
                    cacheLimitMb = st.cacheLimitMb,
                    matchLocalAlbums = st.matchLocalAlbums,
                    albumSuffix = st.albumSuffix,
                    scrobble = s.scrobbler.enabled,
                    pendingScrobbles = s.db.pendingScrobbles().size,
                    ratings = s.scrobbler.ratingsEnabled,
                    wifiOnlyDownloads = st.wifiOnlyDownloads,
                    streamQuality = st.streamQuality,
                    lowerBitrate = st.lowerBitrate,
                )
            }
            // Refreshes overlap; only the library and settings fields come from here, so a toast or
            // connect state set meanwhile is not overwritten with an older copy.
            _state.update { cur -> loaded.copy(connecting = cur.connecting, connectError = cur.connectError, toast = cur.toast) }
        }
    }

    /**
     * Checks the login with a ping before saving anything, then starts the first sync. An address
     * without http:// or https:// is tried as https first; plain http is tried only for LAN addresses.
     */
    fun connect(url: String, user: String, password: String, trustAll: Boolean, onConnected: () -> Unit) {
        _state.update { it.copy(connecting = true, connectError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (salt, token) = SubsonicClient.makeToken(password)
                    val typed = url.trim()
                    val candidates = if (SubsonicClient.hasScheme(typed)) listOf(typed)
                    else listOfNotNull("https://$typed", "http://$typed".takeIf { SubsonicClient.isLocalAddress(typed) })
                    var error: Exception? = null
                    for (candidate in candidates) {
                        var pin = ""
                        val info = try {
                            SubsonicClient(candidate, user.trim(), salt, token, trustAll) { pin = it }.ping()
                        } catch (e: SubsonicException) {
                            throw e // the server answered (e.g. wrong password): no other address to try
                        } catch (e: Exception) {
                            error = e
                            continue
                        }
                        val newUrl = SubsonicClient.normalizeUrl(candidate)
                        val st = s.settings
                        val sameAccount = st.serverUrl.isBlank() ||
                            (sameServer(st.serverUrl, newUrl) && st.username == user.trim())
                        if (!sameAccount) s.forgetLibrary()
                        st.serverUrl = newUrl
                        st.username = user.trim()
                        st.salt = salt
                        st.token = token
                        st.trustAllCerts = trustAll
                        st.certPin = pin
                        st.serverVersion = info.label
                        return@runCatching
                    }
                    val e = error ?: java.io.IOException("No address to try")
                    if (!SubsonicClient.hasScheme(typed) && !SubsonicClient.isLocalAddress(typed)) {
                        throw java.io.IOException(
                            "Could not reach https://$typed (${e.message}). If the server only speaks plain http, " +
                                "type http:// in front; your login will then travel unencrypted.", e,
                        )
                    }
                    throw e
                }
            }
            _state.update { it.copy(connecting = false, connectError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }) }
            if (result.isSuccess) {
                refresh()
                onConnected()
            }
        }
    }

    fun setScrobble(on: Boolean) {
        s.scrobbler.enabled = on
        refresh()
    }

    fun setRatings(on: Boolean) {
        s.scrobbler.ratingsEnabled = on
        refresh()
    }

    fun setWifiOnly(on: Boolean) {
        s.settings.wifiOnlyDownloads = on
        refresh()
    }

    fun setStreamQuality(q: StreamQuality) {
        s.settings.streamQuality = q
        refresh()
    }

    fun setLowerBitrate(kbps: Int) {
        s.settings.lowerBitrate = kbps
        refresh()
    }

    /** True once after a sync finished: time to ask Poweramp to rescan. */
    fun takeRescanPending(): Boolean = s.rescanPending.getAndSet(false)

    /** Sends plays that waited while offline. */
    fun flushScrobbles() = io { if (s.settings.isConfigured) s.scrobbler.flush() }

    /** Turning it on (again) pins the certificate the server presents next, e.g. after renewing it. */
    fun setTrustAll(on: Boolean) {
        s.settings.certPin = ""
        s.settings.trustAllCerts = on
        refresh()
    }

    fun logout() {
        s.settings.logout()
        refresh()
    }

    fun setAlbumPinned(albumId: String, pinned: Boolean, startDownloads: () -> Unit) =
        io(after = { if (pinned) startDownloads() }) { s.db.setPin("album", albumId, pinned) }

    fun setAllOffline(on: Boolean, startDownloads: () -> Unit) =
        io(after = { if (on) startDownloads() }) { s.db.setPin("all", "", on) }

    fun clearCache(keepOffline: Boolean, onDone: () -> Unit) = io(after = onDone) {
        val freed = s.cache.clear(keepOffline)
        s.sync.notifyChanged()
        _state.update { it.copy(toast = "Freed ${formatBytes(freed)}") }
    }

    fun setPrefetch(p: Prefetch) {
        s.settings.prefetch = p
        refresh()
    }

    fun setCacheLimit(mb: Long) = io {
        s.settings.cacheLimitMb = mb.coerceAtLeast(0)
        s.cache.enforceLimit()
    }

    fun setAlbumMatching(on: Boolean, suffix: String = s.settings.albumSuffix, onChanged: () -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { s.sync.configureAlbumMatching(on, suffix) } }
            result.onSuccess { onChanged() }.onFailure { e ->
                _state.update { it.copy(toast = e.message ?: "Could not read the Poweramp library") }
            }
            refresh()
        }
    }

    fun toastShown() {
        _state.update { it.copy(toast = null) }
    }

    /** Runs [block] off the main thread, then [after] on it (callbacks there may start activities). */
    private fun io(after: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block() }
            after()
            refresh()
        }
    }
}

/** Same server whether or not the address was saved with a trailing slash or a different scheme case. */
private fun sameServer(a: String, b: String): Boolean =
    SubsonicClient.normalizeUrl(a).equals(SubsonicClient.normalizeUrl(b), ignoreCase = true)

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

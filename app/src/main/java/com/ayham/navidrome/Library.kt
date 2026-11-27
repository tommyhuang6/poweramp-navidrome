package com.ayham.navidrome

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.util.Log
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.PowerampAPIHelper
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Album covers, fetched once at Poweramp's preferred 1024 px and kept for offline use. */
class CoverCache(private val s: Services) {
    private val dir = File(s.ctx.filesDir, "covers").apply { mkdirs() }

    /** The cover if it is already on the phone; never touches the network. */
    fun cached(coverArtId: String): File? =
        File(dir, CacheManager.safeName(coverArtId) + ".img").takeIf { it.isFile && it.length() > 0 }

    /** One lock per cover, so a thumbnail request and a finished download never fetch the same cover twice. */
    private val locks = ConcurrentHashMap<String, Any>()

    fun file(coverArtId: String): File? {
        cached(coverArtId)?.let { return it }
        val lock = locks.computeIfAbsent(coverArtId) { Any() }
        try {
            synchronized(lock) {
                cached(coverArtId)?.let { return it } // fetched by another thread meanwhile
                val f = File(dir, CacheManager.safeName(coverArtId) + ".img")
                val bytes = try {
                    s.client()?.coverArt(coverArtId, 1024)
                } catch (e: Exception) {
                    Log.w("CoverCache", "cover $coverArtId: $e")
                    null
                } ?: return null
                val tmp = File.createTempFile(f.name, ".tmp", dir)
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) {
                    tmp.delete()
                    return cached(coverArtId)
                }
                return f
            }
        } finally {
            locks.remove(coverArtId, lock)
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

/** Lyrics are fetched the first time Poweramp asks for them and then served from the database. */
class LyricsRepo(private val s: Services) {
    fun get(row: SongRow): Lyrics? {
        s.db.lyrics(row.song.id)?.let { return it }
        if (row.hasLyrics == 0) return null
        return try {
            val l = s.client()?.lyrics(row.song.id, row.song.artist, row.song.title) ?: return null
            s.db.saveLyrics(row.song.id, l)
            l
        } catch (e: Exception) {
            Log.w("LyricsRepo", "lyrics ${row.song.id}: $e")
            null
        }
    }
}

class SyncManager(private val s: Services) {
    @Volatile private var coverRevision = 0L

    /** Full library sync. Throws on network or auth errors. */
    @Synchronized
    fun run(progress: (String) -> Unit): LibraryDb.SyncResult {
        val c = s.client() ?: throw IllegalStateException("No server set up")
        progress("Contacting server…")
        s.settings.serverVersion = c.ping().label
        progress("Loading artists…")
        val artists = c.artists()
        progress("Loading albums…")
        val albums = c.albums()
        val listed = c.songs { n -> progress("Loading songs… $n") }
        // search3 with an empty query lists the whole library on Navidrome, but other servers may
        // return nothing or only part of it. When it falls short of the albums' own song counts,
        // ask album by album instead.
        val expected = albums.sumOf { it.songCount }
        val songs = if (expected > 0 && listed.size < expected * 95L / 100) {
            val byAlbum = albums.flatMapIndexed { i, a ->
                if (i % 20 == 0) progress("Loading songs by album… $i / ${albums.size}")
                c.albumSongs(a.id)
            }.distinctBy { it.id }
            if (byAlbum.size > listed.size) byAlbum else listed
        } else {
            listed
        }
        // An empty listing would delete every song that isn't cached (and with it Poweramp's play
        // counts and playlist entries). An empty server is far less likely than a broken answer.
        if (songs.isEmpty() && (albums.isNotEmpty() || s.db.counts().songs > 0)) {
            throw IOException("The server listed no songs, so the library on the phone was left as it is.")
        }
        progress("Loading playlists…")
        val playlists = c.playlists()
        val radios = runCatching { c.radios() }.getOrDefault(emptyList())
        val local = if (s.settings.matchLocalAlbums) {
            progress("Matching local albums…")
            localAlbums()
        } else null
        progress("Saving…")
        val result = s.db.applySync(artists, albums, songs, s.db.completeCacheIds(), local, s.settings.effectiveAlbumSuffix)
        coverRevision++
        s.db.applyPlaylists(playlists, radios)
        s.settings.lastSync = System.currentTimeMillis()
        notifyChanged()
        return result
    }

    private fun localAlbums(): LocalAlbums = try {
        readLocalAlbums(s.ctx)
    } catch (e: SecurityException) {
        throw IOException("Poweramp library access is required. Tap Grant library access and try again.", e)
    }

    @Synchronized
    fun configureAlbumMatching(enabled: Boolean) {
        s.db.updateAlbumDisplay(if (enabled) localAlbums() else null, s.settings.effectiveAlbumSuffix)
        coverRevision++
        s.settings.matchLocalAlbums = enabled
        notifyChanged()
    }

    @Synchronized
    fun configureAlbumSuffix(enabled: Boolean, suffix: String) {
        val text = suffix.ifBlank { " · ND" }
        s.db.updateAlbumDisplay(if (s.settings.matchLocalAlbums) localAlbums() else null,
            if (enabled) text else "", forceRematch = true)
        coverRevision++
        s.settings.albumSuffix = text
        s.settings.albumSuffixEnabled = enabled
        notifyChanged()
    }

    /** Album and artist folder covers, so Poweramp's folder views keep their artwork offline. */
    fun prefetchFolderCovers(progress: (String) -> Unit) {
        val revision = coverRevision
        val ids = s.db.folderCoverIds()
        ids.forEachIndexed { i, id ->
            if (coverRevision != revision) return // Matching changed while a previous cover was downloading.
            if (i % 10 == 0) progress("Covers… $i / ${ids.size}")
            s.covers.file(id)
        }
    }

    /** Tells Poweramp (and the Android file picker) that our folders changed. */
    fun notifyChanged() {
        s.ctx.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(s.authority, NavidromeProvider.ROOT_DOC), null,
        )
        s.changed()
    }
}

/** Calls into Poweramp. Poweramp ignores scan requests from the background, so these run from the UI. */
object PowerampBridge {
    fun isInstalled(a: Activity): Boolean = PowerampAPIHelper.getPowerampPackageName(a) != null

    /**
     * Rescans only our provider ("@authority/"), in fast mode. resolvePlaylists makes Poweramp link
     * playlist entries again, in case a playlist was parsed before its tracks were in the library.
     */
    fun rescan(a: Activity) {
        val pkg = PowerampAPIHelper.getPowerampPackageName(a) ?: return
        val intent = Intent(PowerampAPI.Scanner.ACTION_SCAN_DIRS)
            .putExtra(PowerampAPI.Scanner.EXTRA_CAUSE, a.packageName + " rescan")
            .putExtra(PowerampAPI.Scanner.EXTRA_PATH, "@" + App.services(a).authority + "/")
            .putExtra(PowerampAPI.Scanner.EXTRA_RESOLVE_PLAYLISTS, true)
            .putExtra(PowerampAPI.EXTRA_PACKAGE, a.packageName)
            .setComponent(ComponentName(pkg, PowerampAPI.API_ACTIVITY_NAME))
        runCatching { a.startActivity(intent) }.onFailure { Log.w("PowerampBridge", "rescan", it) }
    }

    /** Opens Poweramp's Music Folders screen, where the user ticks "Navidrome". */
    fun openMusicFolders(a: Activity) {
        val pkg = PowerampAPIHelper.getPowerampPackageName(a) ?: return
        val intent = Intent()
            .setComponent(ComponentName(pkg, PowerampAPI.ACTIVITY_SETTINGS))
            .putExtra(PowerampAPI.Settings.EXTRA_OPEN_PATH, "library/music_folders_button")
            .putExtra(PowerampAPI.Settings.EXTRA_NO_BACKSTACK, true)
            .putExtra(PowerampAPI.EXTRA_PACKAGE, a.packageName)
        runCatching { a.startActivity(intent) }.onFailure { Log.w("PowerampBridge", "music folders", it) }
    }

    fun isIgnoringBatteryOptimizations(a: Activity): Boolean =
        a.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(a.packageName)

    fun requestBatteryExemption(a: Activity) {
        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.packageName))
        runCatching { a.startActivity(intent) }
            .onFailure { runCatching { a.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }
}

package com.ayham.navidrome

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A song as stored locally: the server's data plus our bookkeeping. */
data class SongRow(
    val song: Song,
    val modified: Long,
    val removed: Boolean,
    /** -1 unknown, 0 none, 1 has lyrics */
    val hasLyrics: Int,
    val displaySuffix: String,
    val localPath: String?,
)

data class FolderRow(val id: String, val name: String, val coverArt: String?, val modified: Long, val removed: Boolean)

data class CacheEntry(
    val songId: String,
    val file: String,
    val bytes: Long,
    val complete: Boolean,
    val pinned: Boolean,
    val lastPlayed: Long,
    val stale: Boolean,
)

data class AlbumSummary(
    val id: String,
    val name: String,
    val artist: String,
    val songs: Int,
    val cached: Int,
    val pinned: Boolean,
)

data class LibraryCounts(
    val artists: Int,
    val albums: Int,
    val songs: Int,
    val cachedSongs: Int,
    val cachedBytes: Long,
    val removed: Int,
    val playlists: Int = 0,
    val radios: Int = 0,
    val hiddenArtists: Int = 0,
    val hiddenAlbums: Int = 0,
    val hiddenSongs: Int = 0,
)

/**
 * Local copy of the library. The provider answers Poweramp only from here, so scans never wait on
 * the network.
 */
class LibraryDb(ctx: Context) : SQLiteOpenHelper(ctx, "library.db", null, 14) {
    private val meta = ctx.getSharedPreferences("library_meta", Context.MODE_PRIVATE)

    /** Set by an upgrade that needs Poweramp to rescan (see onUpgrade). */
    @Volatile var resendTags = false
        private set


    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE songs(
                id TEXT PRIMARY KEY, title TEXT, artist TEXT, album TEXT, albumId TEXT, artistId TEXT,
                albumArtist TEXT, track INTEGER, disc INTEGER, year INTEGER, genre TEXT, duration INTEGER,
                size INTEGER, suffix TEXT, contentType TEXT, bitRate INTEGER, bitDepth INTEGER, coverArt TEXT,
                metaHash TEXT, modified INTEGER, removed INTEGER DEFAULT 0, hasLyrics INTEGER DEFAULT -1,
                seen INTEGER DEFAULT 1, displaySuffix TEXT NOT NULL DEFAULT '', localPath TEXT,
                sampleRate INTEGER NOT NULL DEFAULT 0, channelCount INTEGER NOT NULL DEFAULT 0,
                composer TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX songs_album ON songs(albumId)")
        db.execSQL(
            """CREATE TABLE albums(id TEXT PRIMARY KEY, name TEXT, artist TEXT, artistId TEXT, coverArt TEXT,
                year INTEGER, removed INTEGER DEFAULT 0, seen INTEGER DEFAULT 1)"""
        )
        db.execSQL("CREATE INDEX albums_artist ON albums(artistId)")
        db.execSQL(
            "CREATE TABLE artists(id TEXT PRIMARY KEY, name TEXT, coverArt TEXT, removed INTEGER DEFAULT 0, seen INTEGER DEFAULT 1)"
        )
        db.execSQL(
            """CREATE TABLE cache(songId TEXT PRIMARY KEY, file TEXT, bytes INTEGER, complete INTEGER,
                pinned INTEGER DEFAULT 0, lastPlayed INTEGER DEFAULT 0, stale INTEGER DEFAULT 0)"""
        )
        // Pinned groups: kind = album | artist | all. New songs in a pinned group download on sync.
        db.execSQL("CREATE TABLE pins(kind TEXT, id TEXT, PRIMARY KEY(kind, id))")
        db.execSQL("CREATE TABLE lyrics(songId TEXT PRIMARY KEY, synced TEXT, plain TEXT, fetched INTEGER)")
        createScrobbles(db)
        createPlaylistsAndRatings(db)
        createMatchCache(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createScrobbles(db)
        if (oldVersion < 3) createPlaylistsAndRatings(db)
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE songs ADD COLUMN displaySuffix TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE songs ADD COLUMN localPath TEXT")
            // Refresh all tags, including the legacy global album suffix on upgrades from v7.
            db.execSQL("UPDATE songs SET modified = ?", arrayOf(System.currentTimeMillis()))
            resendTags = true
        }
        if (oldVersion < 9) {
            createMatchCache(db)
            // Older classifications did not check format and size.
            db.execSQL("UPDATE songs SET localPath = NULL, displaySuffix = '', modified = ? " +
                "WHERE localPath IS NOT NULL OR displaySuffix != ''", arrayOf(System.currentTimeMillis()))
            resendTags = true
        }
        if (oldVersion == 9) db.delete("album_match_inputs", null, null) // Recheck size-based results on the next match.
        if (oldVersion < 13) {
            // Local releases used schema 12 with the v10 columns; an earlier metadata build
            // used schema 11 with these columns already present. Accept both upgrade paths.
            val columns = db.rawQuery("PRAGMA table_info(songs)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
            for ((name, definition) in listOf("sampleRate" to "INTEGER NOT NULL DEFAULT 0",
                "channelCount" to "INTEGER NOT NULL DEFAULT 0", "composer" to "TEXT NOT NULL DEFAULT ''")) {
                if (name !in columns) db.execSQL("ALTER TABLE songs ADD COLUMN $name $definition")
            }
            // TRACK now includes the disc number. Re-send even unchanged, already synced tracks.
            db.execSQL("UPDATE songs SET modified = ?", arrayOf(System.currentTimeMillis()))
            resendTags = true
        }
        if (oldVersion < 14) {
            // Recheck classifications saved by releases that required matching durations.
            db.delete("album_match_inputs", null, null)
        }
    }

    private fun createMatchCache(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE album_match_inputs(albumId TEXT NOT NULL, album TEXT NOT NULL,
            digest TEXT NOT NULL, PRIMARY KEY(albumId, album))""")
        // Cache eviction can delete retained songs outside a sync.
        db.execSQL("""CREATE TRIGGER invalidate_deleted_track AFTER DELETE ON songs BEGIN
            DELETE FROM album_match_inputs WHERE albumId = OLD.albumId; END""")
    }

    private fun createPlaylistsAndRatings(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE playlists(id TEXT PRIMARY KEY, name TEXT, changed TEXT, modified INTEGER)")
        db.execSQL("CREATE TABLE playlist_songs(playlistId TEXT, pos INTEGER, songId TEXT)")
        db.execSQL("CREATE TABLE radios(id TEXT PRIMARY KEY, name TEXT, url TEXT)")
        // Last Poweramp rating seen per song: a change from it is what gets sent to Navidrome.
        db.execSQL("CREATE TABLE ratings(songId TEXT PRIMARY KEY, rating INTEGER)")
    }

    /** Plays waiting to be sent to Navidrome (offline, or the server was unreachable). */
    private fun createScrobbles(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE scrobbles(rowId INTEGER PRIMARY KEY AUTOINCREMENT, songId TEXT, time INTEGER)")
    }

    // ---------------------------------------------------------------- songs

    private val songCols = "s.id, s.title, s.artist, s.album, s.albumId, s.artistId, s.albumArtist, s.track, " +
        "s.disc, s.year, s.genre, s.duration, s.size, s.suffix, s.contentType, s.bitRate, s.bitDepth, " +
        "s.coverArt, s.modified, s.removed, s.hasLyrics, s.displaySuffix, s.localPath, " +
        "s.sampleRate, s.channelCount, s.composer"

    private fun Cursor.toSongRow() = SongRow(
        Song(
            id = getString(0), title = getString(1) ?: "", artist = getString(2) ?: "", album = getString(3) ?: "",
            albumId = getString(4) ?: "", artistId = getString(5) ?: "", albumArtist = getString(6) ?: "",
            track = getInt(7), disc = getInt(8), year = getInt(9), genre = getString(10) ?: "",
            durationMs = getLong(11), size = getLong(12), suffix = getString(13) ?: "",
            contentType = getString(14) ?: "", bitRate = getInt(15), bitDepth = getInt(16), coverArt = getString(17),
            sampleRate = getInt(23), channelCount = getInt(24), composer = getString(25).orEmpty(),
        ),
        modified = getLong(18), removed = getInt(19) != 0, hasLyrics = getInt(20),
        displaySuffix = getString(21), localPath = getString(22),
    )

    fun song(id: String): SongRow? =
        readableDatabase.rawQuery("SELECT $songCols FROM songs s WHERE s.id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) it.toSongRow() else null
        }

    fun albumSongs(albumId: String, visibleOnly: Boolean = false): List<SongRow> =
        readableDatabase.rawQuery(
            "SELECT $songCols FROM songs s WHERE s.albumId = ? " +
                (if (visibleOnly) "AND s.localPath IS NULL " else "") + "ORDER BY s.disc, s.track, s.title COLLATE NOCASE",
            arrayOf(albumId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSongRow()) } }

    fun songsWhere(where: String, args: Array<String> = emptyArray()): List<SongRow> =
        readableDatabase.rawQuery("SELECT $songCols FROM songs s WHERE $where", args).use { c ->
            buildList { while (c.moveToNext()) add(c.toSongRow()) }
        }

    fun setHasLyrics(songId: String, has: Boolean) {
        writableDatabase.execSQL("UPDATE songs SET hasLyrics = ? WHERE id = ?", arrayOf<Any>(if (has) 1 else 0, songId))
    }

    // ---------------------------------------------------------------- folders

    /** Artists that still have at least one song, with the newest song change for LAST_MODIFIED. */
    fun artistFolders(): List<FolderRow> =
        readableDatabase.rawQuery(
            """SELECT al.artistId, COALESCE(ar.name, MAX(al.artist)), ar.coverArt, MAX(s.modified),
                      MIN(COALESCE(ar.removed, 0))
               FROM albums al JOIN songs s ON s.albumId = al.id
               LEFT JOIN artists ar ON ar.id = al.artistId
               GROUP BY al.artistId HAVING COUNT(*) > COUNT(s.localPath) ORDER BY 2 COLLATE NOCASE""",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(FolderRow(c.getString(0) ?: "", c.getString(1) ?: "Unknown artist", c.getString(2), c.getLong(3), c.getInt(4) != 0))
            }
        }

    fun artistFolder(artistId: String): FolderRow? = artistFolders().firstOrNull { it.id == artistId }

    fun albumFolders(artistId: String): List<FolderRow> =
        readableDatabase.rawQuery(
            """SELECT al.id, al.name || MAX(s.displaySuffix), al.coverArt, MAX(s.modified), al.removed
               FROM albums al JOIN songs s ON s.albumId = al.id
               WHERE COALESCE(al.artistId, '') = ? GROUP BY al.id HAVING COUNT(*) > COUNT(s.localPath) ORDER BY al.year, al.name COLLATE NOCASE""",
            arrayOf(artistId),
        ).use { c ->
            buildList { while (c.moveToNext()) add(FolderRow(c.getString(0), c.getString(1) ?: "", c.getString(2), c.getLong(3), c.getInt(4) != 0)) }
        }

    fun albumFolder(albumId: String): Pair<FolderRow, String>? =
        readableDatabase.rawQuery(
            """SELECT al.id, al.name || MAX(s.displaySuffix), al.coverArt, MAX(s.modified), al.removed, COALESCE(al.artistId, '')
               FROM albums al JOIN songs s ON s.albumId = al.id WHERE al.id = ? GROUP BY al.id HAVING COUNT(*) > COUNT(s.localPath)""",
            arrayOf(albumId),
        ).use { c ->
            if (!c.moveToFirst()) null
            else FolderRow(c.getString(0), c.getString(1) ?: "", c.getString(2), c.getLong(3), c.getInt(4) != 0) to c.getString(5)
        }

    fun rootModified(): Long =
        readableDatabase.rawQuery("SELECT MAX(modified) FROM songs", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    private val visibleAlbumsSql = """
        SELECT al.id, al.artistId, al.coverArt FROM albums al
        WHERE EXISTS (SELECT 1 FROM songs s WHERE s.albumId = al.id AND s.localPath IS NULL)
    """.trimIndent()

    fun artistCoverArt(artistId: String): String? =
        readableDatabase.rawQuery(
            """SELECT COALESCE(ar.coverArt, al.coverArt) FROM ($visibleAlbumsSql) al
               LEFT JOIN artists ar ON ar.id = al.artistId
               WHERE COALESCE(al.artistId, '') = ? AND COALESCE(ar.coverArt, al.coverArt) IS NOT NULL
               ORDER BY al.id LIMIT 1""",
            arrayOf(artistId),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    /** Only folders with visible songs contribute artwork. UNION also deduplicates shared covers. */
    fun folderCoverIds(): List<String> =
        readableDatabase.rawQuery(
            """WITH visible AS ($visibleAlbumsSql)
               SELECT coverArt FROM visible WHERE coverArt IS NOT NULL
               UNION SELECT ar.coverArt FROM artists ar JOIN visible ON visible.artistId = ar.id
               WHERE ar.coverArt IS NOT NULL""",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun albumCoverArt(albumId: String): String? =
        readableDatabase.rawQuery("SELECT coverArt FROM ($visibleAlbumsSql) WHERE id = ?", arrayOf(albumId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun trackCoverIds(songId: String): List<String> =
        readableDatabase.rawQuery(
            """SELECT s.coverArt, al.coverArt FROM songs s LEFT JOIN albums al ON al.id = s.albumId
               WHERE s.id = ? AND s.localPath IS NULL""",
            arrayOf(songId),
        ).use { c -> if (c.moveToFirst()) listOfNotNull(c.getString(0), c.getString(1)).distinct() else emptyList() }

    // ---------------------------------------------------------------- sync

    class SyncResult(val added: Int, val changed: Int, val removedKept: Int, val removedDropped: Int, val staleFiles: Int, val rematchedAlbums: Int)

    /**
     * Merges a full server listing. Songs gone from the server stay (marked removed) if they are
     * cached, otherwise they are dropped. A song whose file changed keeps its old cached copy, marked
     * stale, until the new one has downloaded.
     */
    internal fun applySync(
        artists: List<Artist>, albums: List<Album>, songs: List<Song>, cachedIds: Set<String>,
        local: LocalAlbums?, suffix: String,
    ): SyncResult {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var added = 0; var changed = 0; var kept = 0; var dropped = 0; var stale = 0
        val rematched: Int
        db.beginTransaction()
        try {
            db.execSQL("UPDATE albums SET seen = 0")
            db.execSQL("UPDATE artists SET seen = 0")

            for (a in artists) {
                db.insertWithOnConflict("artists", null, ContentValues().apply {
                    put("id", a.id); put("name", a.name); put("coverArt", a.coverArt); put("removed", 0); put("seen", 1)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (a in albums) {
                db.insertWithOnConflict("albums", null, ContentValues().apply {
                    put("id", a.id); put("name", a.name); put("artist", a.artist); put("artistId", a.artistId)
                    put("coverArt", a.coverArt); put("year", a.year); put("removed", 0); put("seen", 1)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }

            // Compare the full server metadata. Most rows on a repeat sync need no write at all.
            val pending = songs.associateByTo(LinkedHashMap()) { it.id }
            val previous = mutableMapOf<String, SongRow>()
            db.rawQuery("SELECT $songCols FROM songs s", null).use { c ->
                while (c.moveToNext()) {
                    val row = c.toSongRow()
                    if (!row.removed && pending[row.song.id] == row.song) pending.remove(row.song.id)
                    else previous[row.song.id] = row
                }
            }
            val touchedAlbums = HashSet<String>()
            for (s in pending.values) {
                val prev = previous.remove(s.id)
                val cv = ContentValues().apply {
                    put("title", s.title); put("artist", s.artist); put("album", s.album); put("albumId", s.albumId)
                    put("artistId", s.artistId); put("albumArtist", s.albumArtist); put("track", s.track)
                    put("disc", s.disc); put("year", s.year); put("genre", s.genre); put("duration", s.durationMs)
                    put("size", s.size); put("suffix", s.suffix); put("contentType", s.contentType)
                    put("bitRate", s.bitRate); put("bitDepth", s.bitDepth); put("coverArt", s.coverArt)
                    put("sampleRate", s.sampleRate); put("channelCount", s.channelCount); put("composer", s.composer)
                    put("metaHash", s.metaHash); put("removed", 0); put("seen", 1)
                }
                if (prev == null) {
                    cv.put("id", s.id); cv.put("modified", now)
                    db.insert("songs", null, cv)
                    added++
                    touchedAlbums += s.albumId
                } else {
                    cv.put("modified", now)
                    changed++
                    touchedAlbums += s.albumId
                    touchedAlbums += prev.song.albumId
                    if ((prev.song.size != s.size || prev.song.suffix != s.suffix) && s.id in cachedIds) {
                        db.execSQL("UPDATE cache SET stale = 1 WHERE songId = ?", arrayOf(s.id))
                        stale++
                    }
                    db.update("songs", cv, "id = ?", arrayOf(s.id))
                }
            }

            // Songs no longer on the server.
            for ((id, row) in previous) {
                if (id in cachedIds) {
                    if (row.removed) continue
                    db.execSQL("UPDATE songs SET removed = 1, modified = ? WHERE id = ?", arrayOf<Any>(now, id))
                    kept++
                } else {
                    db.delete("songs", "id = ?", arrayOf(id))
                    db.delete("lyrics", "songId = ?", arrayOf(id))
                    dropped++
                }
                touchedAlbums += row.song.albumId
            }
            for (albumId in touchedAlbums) {
                db.delete("album_match_inputs", "albumId = ?", arrayOf(albumId))
                db.execSQL("UPDATE songs SET modified = ? WHERE albumId = ?", arrayOf<Any>(now, albumId))
            }
            // Albums/artists gone from the server stay only while kept songs still need them.
            db.execSQL("UPDATE albums SET removed = 1 WHERE seen = 0")
            db.execSQL("DELETE FROM albums WHERE seen = 0 AND id NOT IN (SELECT albumId FROM songs)")
            db.execSQL("UPDATE artists SET removed = 1 WHERE seen = 0")
            db.execSQL("DELETE FROM artists WHERE seen = 0 AND id NOT IN (SELECT artistId FROM albums)")
            rematched = updateAlbumDisplay(db, local, suffix, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return SyncResult(added, changed, kept, dropped, stale, rematched)
    }

    internal fun updateAlbumDisplay(local: LocalAlbums?, suffix: String, forceRematch: Boolean = false) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (forceRematch) db.delete("album_match_inputs", null, null)
            updateAlbumDisplay(db, local, suffix, System.currentTimeMillis())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun updateAlbumDisplay(db: SQLiteDatabase, local: LocalAlbums?, suffix: String, now: Long): Int {
        if (local == null) {
            db.execSQL("UPDATE songs SET localPath = NULL, displaySuffix = '', modified = ? " +
                "WHERE localPath IS NOT NULL OR displaySuffix != ''", arrayOf(now))
            db.delete("album_match_inputs", null, null)
            return 0
        }

        val dirty = mutableSetOf<String>()
        db.rawQuery("""SELECT a.id FROM albums a WHERE NOT EXISTS
            (SELECT 1 FROM album_match_inputs i WHERE i.albumId = a.id)
            AND EXISTS (SELECT 1 FROM songs s WHERE s.albumId = a.id)""", null).use { c ->
            while (c.moveToNext()) dirty += c.getString(0)
        }
        db.rawQuery("SELECT albumId, album, digest FROM album_match_inputs", null).use { c ->
            while (c.moveToNext()) if (local.digest(c.getString(1)) != c.getString(2)) dirty += c.getString(0)
        }
        db.execSQL("UPDATE songs SET displaySuffix = ?, modified = ? WHERE displaySuffix != '' AND displaySuffix != ?",
            arrayOf<Any>(suffix, now, suffix))
        // Keep each query below Android SQLite's bind parameter limit, including the first full pass.
        for (ids in dirty.chunked(400)) matchAlbums(db, ids, local, suffix, now)
        return dirty.size
    }

    private fun matchAlbums(db: SQLiteDatabase, ids: List<String>, local: LocalAlbums, suffix: String, now: Long) {
        data class Entry(val id: String, val tags: TrackTags, val oldSuffix: String, val oldPath: String?)
        val albums = mutableMapOf<String, MutableList<Entry>>()
        db.rawQuery("""SELECT s.albumId, s.id, s.album, s.albumArtist,
                       s.title, s.artist, s.disc, s.track, s.duration, s.displaySuffix, s.localPath, s.suffix
                       FROM songs s WHERE s.albumId IN (${ids.joinToString(",") { "?" }})
                       ORDER BY s.id""", ids.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val tags = TrackTags(c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getString(4).orEmpty(),
                    c.getString(5).orEmpty(), c.getInt(6), c.getInt(7), c.getLong(8),
                    c.getString(11).orEmpty().lowercase())
                albums.getOrPut(c.getString(0)) { mutableListOf() }
                    .add(Entry(c.getString(1), tags, c.getString(9), c.getString(10)))
            }
        }
        for ((albumId, entries) in albums) {
            db.delete("album_match_inputs", "albumId = ?", arrayOf(albumId))
            for (name in entries.map { it.tags.album }.distinct()) {
                db.execSQL("INSERT INTO album_match_inputs(albumId, album, digest) VALUES(?,?,?)",
                    arrayOf(albumId, name, local.digest(name)))
            }
            val display = local.display(entries.map { it.id to it.tags }, suffix)
            var changed = false
            for (entry in entries) {
                val path = display.localPaths[entry.id]
                if (entry.oldPath == path && entry.oldSuffix == display.suffix) continue
                db.execSQL("UPDATE songs SET localPath = ?, displaySuffix = ? WHERE id = ?",
                    arrayOf(path, display.suffix, entry.id))
                changed = true
            }
            if (changed) db.execSQL("UPDATE songs SET modified = ? WHERE albumId = ?", arrayOf<Any>(now, albumId))
        }
    }

    /** After the cache is cleared, songs the server no longer has have nothing left to play. */
    fun dropRemovedUncached(): Int {
        val db = writableDatabase
        val gone = "removed = 1 AND id NOT IN (SELECT songId FROM cache WHERE complete = 1)"
        // The rest of those albums must be re-read by Poweramp (see applySync).
        db.execSQL(
            "UPDATE songs SET modified = ? WHERE albumId IN (SELECT albumId FROM songs WHERE $gone)",
            arrayOf<Any>(System.currentTimeMillis()),
        )
        val n = db.delete("songs", gone, null)
        db.execSQL("DELETE FROM albums WHERE removed = 1 AND id NOT IN (SELECT albumId FROM songs)")
        db.execSQL("DELETE FROM artists WHERE removed = 1 AND id NOT IN (SELECT artistId FROM albums)")
        return n
    }

    // ---------------------------------------------------------------- cache

    private fun Cursor.toCacheEntry() = CacheEntry(
        getString(0), getString(1), getLong(2), getInt(3) != 0, getInt(4) != 0, getLong(5), getInt(6) != 0,
    )

    private val cacheCols = "songId, file, bytes, complete, pinned, lastPlayed, stale"

    fun cacheEntry(songId: String): CacheEntry? =
        readableDatabase.rawQuery("SELECT $cacheCols FROM cache WHERE songId = ?", arrayOf(songId)).use {
            if (it.moveToFirst()) it.toCacheEntry() else null
        }

    fun completeCacheIds(): Set<String> =
        readableDatabase.rawQuery("SELECT songId FROM cache WHERE complete = 1", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun allCacheEntries(): List<CacheEntry> =
        readableDatabase.rawQuery("SELECT $cacheCols FROM cache", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toCacheEntry()) }
        }

    /** Also counts as recently used, so a cache limit doesn't evict a prefetched song right away. */
    fun markComplete(songId: String, file: String, bytes: Long) {
        writableDatabase.execSQL(
            """INSERT INTO cache(songId, file, bytes, complete, stale, lastPlayed) VALUES(?, ?, ?, 1, 0, ?)
               ON CONFLICT(songId) DO UPDATE SET file = excluded.file, bytes = excluded.bytes, complete = 1, stale = 0,
               lastPlayed = MAX(lastPlayed, excluded.lastPlayed)""",
            arrayOf<Any>(songId, file, bytes, System.currentTimeMillis()),
        )
    }

    fun touchPlayed(songId: String) {
        writableDatabase.execSQL(
            """INSERT INTO cache(songId, file, bytes, complete, lastPlayed) VALUES(?, '', 0, 0, ?)
               ON CONFLICT(songId) DO UPDATE SET lastPlayed = excluded.lastPlayed""",
            arrayOf<Any>(songId, System.currentTimeMillis()),
        )
    }

    fun deleteCacheEntry(songId: String) {
        writableDatabase.delete("cache", "songId = ?", arrayOf(songId))
    }

    // ---------------------------------------------------------------- pins

    fun pins(): Set<Pair<String, String>> =
        readableDatabase.rawQuery("SELECT kind, id FROM pins", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    fun setPin(kind: String, id: String, pinned: Boolean) {
        if (pinned) writableDatabase.execSQL("INSERT OR IGNORE INTO pins(kind, id) VALUES(?, ?)", arrayOf(kind, id))
        else writableDatabase.delete("pins", "kind = ? AND id = ?", arrayOf(kind, id))
        refreshPinnedFlags()
    }

    /** Recomputes cache.pinned from the pin groups. Returns the pinned songs that are not downloaded yet. */
    fun refreshPinnedFlags(): List<String> {
        val db = writableDatabase
        val pins = pins()
        val all = ("all" to "") in pins
        val albumIds = pins.filter { it.first == "album" }.map { it.second }
        val artistIds = pins.filter { it.first == "artist" }.map { it.second }
        // Songs removed on the server stay pinned when they are cached, so "clear cache (keep offline)"
        // and the cache limit never delete the last copy; they just aren't downloaded any more.
        val pinnedSongs = songsWhere("1").filter { all || it.song.albumId in albumIds || it.song.artistId in artistIds }
        db.beginTransaction()
        try {
            db.execSQL("UPDATE cache SET pinned = 0")
            for (row in pinnedSongs) {
                if (row.removed) {
                    db.execSQL("UPDATE cache SET pinned = 1 WHERE songId = ?", arrayOf(row.song.id))
                } else {
                    db.execSQL(
                        """INSERT INTO cache(songId, file, bytes, complete, pinned) VALUES(?, '', 0, 0, 1)
                           ON CONFLICT(songId) DO UPDATE SET pinned = 1""",
                        arrayOf(row.song.id),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return readableDatabase.rawQuery(
            """SELECT c.songId FROM cache c JOIN songs s ON s.id = c.songId
               WHERE c.pinned = 1 AND (c.complete = 0 OR c.stale = 1) AND s.removed = 0""",
            null,
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    // ---------------------------------------------------------------- lyrics

    fun lyrics(songId: String): Lyrics? =
        readableDatabase.rawQuery("SELECT synced, plain FROM lyrics WHERE songId = ?", arrayOf(songId)).use {
            if (it.moveToFirst()) Lyrics(it.getString(0), it.getString(1)) else null
        }

    fun saveLyrics(songId: String, lyrics: Lyrics) {
        writableDatabase.insertWithOnConflict("lyrics", null, ContentValues().apply {
            put("songId", songId); put("synced", lyrics.synced); put("plain", lyrics.plain); put("fetched", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        setHasLyrics(songId, !lyrics.isEmpty)
    }

    // ---------------------------------------------------------------- playlists and radio

    data class PlaylistRow(val id: String, val name: String, val modified: Long)

    /** Replaces playlists and radios with the server's lists; a playlist's modified time moves only when it changed. */
    fun applyPlaylists(playlists: List<Playlist>, radios: List<Radio>) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val oldChanged = HashMap<String, Pair<String, Long>>()
        db.rawQuery("SELECT id, changed, modified FROM playlists", null).use { c ->
            while (c.moveToNext()) oldChanged[c.getString(0)] = (c.getString(1) ?: "") to c.getLong(2)
        }
        db.beginTransaction()
        try {
            db.delete("playlists", null, null)
            db.delete("playlist_songs", null, null)
            for (p in playlists) {
                val prev = oldChanged[p.id]
                val modified = if (prev != null && prev.first == p.changed) prev.second else now
                db.insert("playlists", null, ContentValues().apply {
                    put("id", p.id); put("name", p.name); put("changed", p.changed); put("modified", modified)
                })
                p.songIds.forEachIndexed { i, songId ->
                    db.insert("playlist_songs", null, ContentValues().apply {
                        put("playlistId", p.id); put("pos", i); put("songId", songId)
                    })
                }
            }
            val radiosChanged = radios.sortedBy { it.id } != radios().sortedBy { it.id }
            db.delete("radios", null, null)
            for (r in radios) {
                db.insert("radios", null, ContentValues().apply { put("id", r.id); put("name", r.name); put("url", r.url) })
            }
            db.setTransactionSuccessful()
            if (radiosChanged) meta.edit().putLong("radiosModified", now).apply()
        } finally {
            db.endTransaction()
        }
    }

    /** When the radio list last changed, so Poweramp re-reads Radio.m3u8. */
    fun radiosModified(): Long = meta.getLong("radiosModified", 0)

    fun playlists(): List<PlaylistRow> =
        readableDatabase.rawQuery("SELECT id, name, modified FROM playlists ORDER BY name COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(PlaylistRow(c.getString(0), c.getString(1) ?: "", c.getLong(2))) }
        }

    fun playlist(id: String): PlaylistRow? = playlists().firstOrNull { it.id == id }

    /** Playlist songs in order, skipping songs this phone doesn't know (not synced yet). */
    fun playlistSongs(id: String): List<SongRow> =
        readableDatabase.rawQuery(
            "SELECT $songCols FROM playlist_songs p JOIN songs s ON s.id = p.songId WHERE p.playlistId = ? ORDER BY p.pos",
            arrayOf(id),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSongRow()) } }

    fun radios(): List<Radio> =
        readableDatabase.rawQuery("SELECT id, name, url FROM radios ORDER BY name COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(Radio(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")) }
        }

    // ---------------------------------------------------------------- ratings

    /** Last Poweramp rating seen for the song, or null if never seen. */
    fun lastRating(songId: String): Int? =
        readableDatabase.rawQuery("SELECT rating FROM ratings WHERE songId = ?", arrayOf(songId)).use {
            if (it.moveToFirst()) it.getInt(0) else null
        }

    fun saveRating(songId: String, rating: Int) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO ratings(songId, rating) VALUES(?, ?)", arrayOf<Any>(songId, rating))
    }

    // ---------------------------------------------------------------- scrobbles

    fun queueScrobble(songId: String, time: Long) {
        writableDatabase.execSQL("INSERT INTO scrobbles(songId, time) VALUES(?, ?)", arrayOf<Any>(songId, time))
    }

    fun pendingScrobbles(): List<Triple<Long, String, Long>> =
        readableDatabase.rawQuery("SELECT rowId, songId, time FROM scrobbles ORDER BY time", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getLong(2))) }
        }

    fun deleteScrobble(rowId: Long) {
        writableDatabase.delete("scrobbles", "rowId = ?", arrayOf(rowId.toString()))
    }

    // ---------------------------------------------------------------- UI

    fun counts(): LibraryCounts = readableDatabase.rawQuery(
        """SELECT (SELECT COUNT(DISTINCT artistId) FROM albums WHERE id IN (SELECT albumId FROM songs)),
                  (SELECT COUNT(DISTINCT albumId) FROM songs),
                  (SELECT COUNT(*) FROM songs),
                  (SELECT COUNT(*) FROM cache WHERE complete = 1),
                  (SELECT COALESCE(SUM(bytes), 0) FROM cache WHERE complete = 1),
                  (SELECT COUNT(*) FROM songs WHERE removed = 1),
                  (SELECT COUNT(*) FROM playlists),
                  (SELECT COUNT(*) FROM radios),
                  (SELECT COUNT(DISTINCT artistId) FROM albums WHERE id IN (SELECT albumId FROM songs WHERE localPath IS NULL)),
                  (SELECT COUNT(DISTINCT albumId) FROM songs WHERE localPath IS NULL),
                  (SELECT COUNT(*) FROM songs WHERE localPath IS NOT NULL)""",
        null,
    ).use { c ->
        c.moveToFirst()
        LibraryCounts(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getInt(5), c.getInt(6), c.getInt(7),
            hiddenArtists = c.getInt(0) - c.getInt(8), hiddenAlbums = c.getInt(1) - c.getInt(9), hiddenSongs = c.getInt(10))
    }

    fun albumSummaries(): List<AlbumSummary> {
        val pinnedAlbums = pins().filter { it.first == "album" }.map { it.second }.toSet()
        return readableDatabase.rawQuery(
            """SELECT al.id, al.name, al.artist, COUNT(s.id),
                      SUM(CASE WHEN c.complete = 1 THEN 1 ELSE 0 END)
               FROM albums al JOIN songs s ON s.albumId = al.id
               LEFT JOIN cache c ON c.songId = s.id
               GROUP BY al.id ORDER BY al.artist COLLATE NOCASE, al.name COLLATE NOCASE""",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    add(AlbumSummary(id, c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getInt(4), id in pinnedAlbums))
                }
            }
        }
    }

    fun removedSongs(): List<SongRow> = songsWhere("s.removed = 1 ORDER BY s.artist, s.title")

    /** Empties everything that belongs to the logged-in account (see Services.forgetLibrary). */
    fun clearLibrary() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in listOf("album_match_inputs", "songs", "albums", "artists", "cache", "pins", "lyrics", "playlists", "playlist_songs", "radios", "ratings", "scrobbles")) {
                db.delete(t, null, null)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        meta.edit().remove("radiosModified").apply()
    }
}

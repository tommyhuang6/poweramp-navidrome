package com.ayham.navidrome

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import com.maxmpz.poweramp.player.PowerampAPI
import org.robolectric.shadows.ShadowContentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LocalAlbumDisplayTest {
    private val suffix = " · ND"

    private fun song(album: Album, n: Int) = Song(
        id = "${album.id}-$n", title = "Song $n", artist = album.artist, album = album.name, albumId = album.id,
        artistId = album.artistId, albumArtist = album.artist, track = n, disc = 1, year = 2024, genre = "",
        durationMs = 180000, size = 1024, suffix = "flac", contentType = "audio/flac", bitRate = 0,
        bitDepth = 16, coverArt = null,
    )

    private fun local(song: Song) = LocalTrack(
        TrackTags(song.album, song.albumArtist, song.title, song.artist, song.disc, song.track, song.durationMs, song.suffix),
        "/storage/emulated/0/Music/${song.id}.flac",
    )

    @Test fun folderTagsPlaylistsAndHiddenCountsFollowAlbumCoverage() {
        val albums = listOf(
            Album("partial", "Partial", "A", "a", null, 2024),
            Album("full", "Full", "B", "b", null, 2024),
            Album("alsoFull", "Also full", "A", "a", null, 2024),
            // Same album title as a local copy by a different artist.
            Album("cloud", "Full", "C", "c", null, 2024),
        )
        val songs = albums.flatMap { album -> (1..12).map { song(album, it) } }
        val local = LocalAlbums(songs.filter {
            it.albumId == "full" || it.albumId == "alsoFull" || (it.albumId == "partial" && it.track <= 8)
        }.map(::local))
        val db = LibraryDb(RuntimeEnvironment.getApplication())
        try {
            db.applySync(emptyList(), albums, songs, emptySet(), local, suffix)
            val counts = db.counts()
            assertEquals(listOf(3, 4, 48), listOf(counts.artists, counts.albums, counts.songs))
            assertEquals(listOf(1, 2, 24), listOf(counts.hiddenArtists, counts.hiddenAlbums, counts.hiddenSongs))
            assertEquals(setOf("a", "c"), db.artistFolders().map { it.id }.toSet())
            assertEquals("Partial$suffix", db.albumFolders("a").single().name)
            assertEquals("Full", db.albumFolder("cloud")!!.first.name)
            assertEquals(null, db.albumFolder("full"))
            assertEquals(12, db.albumSongs("partial", visibleOnly = true).size)
            assertEquals(setOf(suffix), db.albumSongs("partial").map { it.displaySuffix }.toSet())
            assertEquals(0, db.albumSongs("full", visibleOnly = true).size)

            db.applyPlaylists(listOf(Playlist("p", "Mix", "", listOf("full-4", "partial-2"))), emptyList())
            val playlist = db.playlistSongs("p")
            assertEquals("/storage/emulated/0/Music/full-4.flac", playlist[0].localPath)
            assertEquals(null, playlist[1].localPath)

            // Removing local files and then turning off matching both restore cloud entries.
            db.updateAlbumDisplay(LocalAlbums(listOf(local(songs.first { it.albumId == "full" }))), suffix)
            assertEquals(0, db.counts().hiddenSongs)
            assertEquals("Full$suffix", db.albumFolder("full")!!.first.name)
            assertEquals("Partial", db.albumFolder("partial")!!.first.name)
            db.updateAlbumDisplay(null, suffix)
            assertEquals("Full", db.albumFolder("full")!!.first.name)
            assertEquals(null, db.playlistSongs("p").first().localPath)
        } finally {
            db.close()
        }
    }

    @Test fun powerampScanStopsListingAlbumWhenLastTrackMatches() {
        val app: Application = RuntimeEnvironment.getApplication()
        val services = App.services(app)
        val provider = NavidromeProvider().apply {
            attachInfo(app, ProviderInfo().apply {
                authority = services.authority
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            })
        }
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
        fun children(id: String) = provider.queryChildDocuments(id, columns, sortOrder = null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

        val album = Album("record", "Record", "Singer", "artist", null, 2024)
        val songs = (1..12).map { song(album, it) }
        fun localCopy(lastTrackDifferenceMs: Long) = LocalAlbums(songs.map { song ->
            val file = local(song)
            if (song.track != 12) file else file.copy(tags = file.tags.copy(durationMs = song.durationMs + lastTrackDifferenceMs))
        })

        services.db.applySync(emptyList(), listOf(album), songs, emptySet(), localCopy(2001), suffix)
        assertEquals(mapOf("al:record" to "Record$suffix"), children("ar:artist"))
        assertEquals(12, children("al:record").size)
        services.db.updateAlbumDisplay(localCopy(2000), suffix)
        assertEquals(emptyMap<String, String>(), children("ar:artist"))
        assertEquals(emptyMap<String, String>(), children("al:record"))
        assertThrows(FileNotFoundException::class.java) { provider.queryDocument("tr:record-12", columns).close() }
        assertEquals(12, services.db.counts().hiddenSongs)
    }

    @Test fun coverPrefetchAndThumbnailRequestsUseOnlyVisibleTracksAndFolders() {
        val hidden = Album("h", "Hidden", "A", "a", "hidden-cover", 2024)
        val partial = Album("p", "Partial", "A", "a", "partial-cover", 2024)
        val hiddenArtist = Album("b1", "Other hidden", "B", "b", "b-cover", 2024)
        val cloud = Album("c1", "Cloud only", "C", "c", "cloud-cover", 2024)
        val albums = listOf(hidden, partial, hiddenArtist, cloud)
        val songs = albums.flatMap { album -> (1..2).map { song(album, it).copy(coverArt = "track-${album.id}-$it") } }
        val artists = listOf(Artist("a", "A", null), Artist("b", "B", "artist-b"), Artist("c", "C", "artist-c"))
        val local = LocalAlbums(songs.filter {
            it.albumId == hidden.id || it.albumId == hiddenArtist.id || (it.albumId == partial.id && it.track == 1)
        }.map(::local))
        val db = LibraryDb(RuntimeEnvironment.getApplication())
        try {
            db.applySync(artists, albums, songs, emptySet(), local, suffix)
            assertEquals(setOf("partial-cover", "cloud-cover", "artist-c"), db.folderCoverIds().toSet())
            assertEquals(null, db.albumCoverArt(hidden.id))
            assertEquals(null, db.artistCoverArt("b"))
            assertEquals("partial-cover", db.artistCoverArt("a"))
            assertEquals("artist-c", db.artistCoverArt("c"))
            assertEquals(emptyList<String>(), db.trackCoverIds("h-1"))
            assertEquals(listOf("track-p-1", "partial-cover"), db.trackCoverIds("p-1"))
            db.updateAlbumDisplay(null, suffix)
            assertEquals("hidden-cover", db.albumCoverArt(hidden.id))
            assertEquals("artist-b", db.artistCoverArt("b"))
            assertEquals(listOf("track-h-1", "hidden-cover"), db.trackCoverIds("h-1"))
        } finally {
            db.close()
        }
    }

    @Test fun readsLocalAlbumAndTrackTagsAndSkipsProviderTracksAndStreams() {
        val tables = SQLiteDatabase.create(null)
        try {
            tables.execSQL("CREATE TABLE albums(_id, album)")
            tables.execSQL("CREATE TABLE artists(_id, artist)")
            tables.execSQL("CREATE TABLE album_artists(_id, album_artist)")
            tables.execSQL("CREATE TABLE folders(_id, path)")
            tables.execSQL("CREATE TABLE folder_files(album_id, album_artist_id, title_tag, artist_id, disc, track_tag, " +
                "duration, file_path, folder_id, name, url, file_type)")
            tables.execSQL("INSERT INTO albums VALUES (1, 'Record')")
            tables.execSQL("INSERT INTO artists VALUES (2, 'Singer')")
            tables.execSQL("INSERT INTO album_artists VALUES (3, 'Band')")
            val app: Application = RuntimeEnvironment.getApplication()
            val localFile = java.io.File(app.cacheDir, "local.flac")
            tables.execSQL("INSERT INTO folders VALUES (4, ?)", arrayOf(app.cacheDir.path + "/"))
            tables.execSQL("""INSERT INTO folder_files VALUES
                (1,3,'Local',2,1,1,90000,NULL,4,'local.flac',NULL,1),
                (1,3,'Cloud',2,1,2,90000,'@example.provider/tr-2',4,'cloud.flac',NULL,1),
                (1,3,'Radio',2,1,3,90000,NULL,4,'radio','https://example.test/radio',23)""")
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                                   args: Array<out String>?, order: String?): Cursor {
                    val table = if (uri.lastPathSegment == "files") "folder_files" else uri.lastPathSegment!!
                    return tables.query(table, projection, selection, args, null, null, order)
                }
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri = error("Read only")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Read only")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Read only")
            }
            provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = PowerampAPI.AUTHORITY })
            ShadowContentResolver.registerProviderInternal(PowerampAPI.AUTHORITY, provider)
            val library = readLocalAlbums(app)
            val tags = TrackTags("Record", "Band", "Local", "Singer", 1, 1, 90000, "flac")
            assertEquals(mapOf("1" to localFile.path),
                library.display(listOf("1" to tags), suffix).localPaths)
            assertEquals(AlbumDisplay(), library.display(listOf("2" to tags.copy(title = "Cloud", track = 2),
                "3" to tags.copy(title = "Radio", track = 3)), suffix))
        } finally {
            tables.close()
        }
    }

    @Test fun repeatSyncReusesMatchesAcrossRestartAndInvalidatesOnlyAffectedAlbums() {
        val albums = (1..100).map { Album("a$it", "Album $it", "Artist", "artist", null, 2024) }
        val songs = albums.flatMap { album -> (1..12).map { song(album, it) } }
        val files = songs.map(::local)
        val app: Application = RuntimeEnvironment.getApplication()
        var db = LibraryDb(app)
        fun sync(cloud: List<Song>, localFiles: List<LocalTrack>) =
            db.applySync(emptyList(), albums, cloud, emptySet(), LocalAlbums(localFiles), suffix)
        try {
            assertEquals(100, sync(songs, files).rematchedAlbums)
            db.close()
            db = LibraryDb(app)
            assertEquals(0, sync(songs, files.reversed()).rematchedAlbums)
            assertEquals(1200, db.counts().hiddenSongs)

            val movedFile = files[0].copy(path = "/storage/emulated/0/Renamed/different-name.flac")
            val localChanges = listOf(movedFile) + files.drop(2)
            assertEquals(1, sync(songs, localChanges).rematchedAlbums)
            assertEquals(1188, db.counts().hiddenSongs)
            val restored = localChanges + files[1]
            assertEquals(1, sync(songs, restored).rematchedAlbums)
            assertEquals(movedFile.path, db.song(songs[0].id)!!.localPath)

            val cloudChanges = songs.map { if (it.id == "a2-2") it.copy(title = "Different cloud title") else it }
            assertEquals(1, sync(cloudChanges, restored).rematchedAlbums)
            assertEquals(1188, db.counts().hiddenSongs)
            assertEquals(0, sync(cloudChanges, restored).rematchedAlbums)
            val changedBoth = restored.map { if (it == files.last()) it.copy(tags = it.tags.copy(title = "Retagged")) else it }
            val deleted = cloudChanges.filter { it.id != "a3-1" }
            assertEquals(2, sync(deleted, changedBoth).rematchedAlbums)
            assertEquals(1175, db.counts().hiddenSongs)
        } finally {
            db.close()
        }
    }

    @Test fun upgradeRechecksAlbumsHiddenUnderPreviousMatchingRules() {
        val app: Application = RuntimeEnvironment.getApplication()
        val album = Album("record", "Record", "Singer", "artist", null, 2024)
        val songs = listOf(song(album, 1))
        val db = LibraryDb(app)
        try {
            db.applySync(emptyList(), listOf(album), songs, emptySet(), LocalAlbums(songs.map(::local)), suffix)
            // Reproduce the v8 schema with a previous match from before format was checked.
            db.writableDatabase.execSQL("DROP TRIGGER invalidate_deleted_track")
            db.writableDatabase.execSQL("DROP TABLE album_match_inputs")
            db.writableDatabase.version = 8
        } finally {
            db.close()
        }
        val upgraded = LibraryDb(app)
        try {
            assertEquals(0, upgraded.counts().hiddenSongs)
            val mismatch = local(songs.single()).let { it.copy(tags = it.tags.copy(format = "mp3")) }
            val result = upgraded.applySync(emptyList(), listOf(album), songs, emptySet(), LocalAlbums(listOf(mismatch)), suffix)
            assertEquals(1, result.rematchedAlbums)
            assertEquals(0, upgraded.counts().hiddenSongs)
        } finally {
            upgraded.close()
        }
    }

    @Test fun v9UpgradeRechecksVisibleAlbumAndAcceptsDifferentOrMissingSizes() {
        val app: Application = RuntimeEnvironment.getApplication()
        val album = Album("record", "Record", "Singer", "artist", null, 2024)
        val original = song(album, 1)
        val library = LocalAlbums(listOf(local(original)))
        val db = LibraryDb(app)
        try {
            db.applySync(emptyList(), listOf(album), listOf(original), emptySet(), library, suffix)
            // A cached non-match from the size-based release must be revisited after upgrading.
            db.writableDatabase.execSQL("UPDATE songs SET localPath = NULL")
            db.writableDatabase.version = 9
        } finally {
            db.close()
        }
        val upgraded = LibraryDb(app)
        try {
            fun sync(s: Song) = upgraded.applySync(emptyList(), listOf(album), listOf(s), emptySet(), library, suffix)
            assertEquals(1, sync(original).rematchedAlbums)
            assertEquals(1, upgraded.counts().hiddenSongs)
            for (size in listOf(123456789L, 0L)) {
                sync(original.copy(size = size))
                assertEquals(1, upgraded.counts().hiddenSongs)
            }
            assertEquals(0, sync(original.copy(size = 0)).rematchedAlbums)
        } finally {
            upgraded.close()
        }
    }

    @Test fun matchUsesTagsFormatAndDurationIndependentlyOfBaseFilename() {
        val original = TrackTags("Album", "Artist", "Song", "Singer", 1, 1, 185000, "flac")
        val file = LocalTrack(original, "/sdcard/Music/arbitrary-name.flac")
        val index = LocalAlbums(listOf(file))
        fun hidden(tags: TrackTags) = index.display(listOf("song-id" to tags), suffix).localPaths
        assertEquals(mapOf("song-id" to file.path), hidden(original))
        assertEquals(mapOf("song-id" to file.path), hidden(original.copy(durationMs = 183000)))
        assertEquals(mapOf("song-id" to file.path), hidden(original.copy(durationMs = 187000)))
        for (different in listOf(original.copy(durationMs = 182999), original.copy(format = "mp3"),
            original.copy(durationMs = 187001), original.copy(durationMs = 0),
            original.copy(title = "Different"), original.copy(disc = 0), original.copy(track = 0))) {
            assertEquals(emptyMap<String, String>(), hidden(different))
        }
    }

    @Test fun onePhysicalTrackCannotHideTwoIdenticalCloudTracks() {
        val tags = TrackTags("Album", "Artist", "Title", "Artist", 0, 0, 120000, "flac")
        val cloud = listOf("1" to tags, "2" to tags)
        val file = LocalTrack(tags, "/music/one.flac")
        assertEquals(AlbumDisplay(suffix), LocalAlbums(listOf(file, file)).display(cloud, suffix))
    }
}

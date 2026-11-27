package com.ayham.navidrome

import android.app.Application
import android.content.pm.ProviderInfo
import android.database.sqlite.SQLiteDatabase
import android.media.MediaFormat
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import com.maxmpz.poweramp.player.TrackProviderConsts
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ProviderMetadataTest {
    private val album = Album("album", "Two discs", "Artist", "artist", null, 2024)
    private val client = SubsonicClient("https://example.com", "", "", "", false)

    private fun song(disc: Int = 2, track: Int = 5) = client.parseSong(JSONObject("""{
        "id":"song", "title":"Song", "artist":"Artist", "album":"Two discs",
        "albumId":"album", "artistId":"artist", "displayAlbumArtist":"Artist",
        "discNumber":$disc, "track":$track, "duration":212, "size":123456,
        "suffix":"flac", "contentType":"audio/flac", "bitRate":790, "bitDepth":16,
        "samplingRate":44100, "channelCount":2, "displayComposer":"Composer"
    }"""))

    private fun provider(): Pair<Services, NavidromeProvider> {
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
        return services to provider
    }

    @Test fun apiTagsSurviveStorageAndUseOfficialProviderColumnsAndUnits() {
        val (services, provider) = provider()
        val track = song()
        services.db.applySync(emptyList(), listOf(album), listOf(track), emptySet(), null, " · ND")
        assertEquals(track, services.db.song(track.id)!!.song)
        provider.queryDocument("tr:song", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2005, c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TRACK)))
            assertEquals(44100, c.getInt(c.getColumnIndexOrThrow(MediaFormat.KEY_SAMPLE_RATE)))
            assertEquals(2, c.getInt(c.getColumnIndexOrThrow(MediaFormat.KEY_CHANNEL_COUNT)))
            assertEquals(790000, c.getInt(c.getColumnIndexOrThrow(MediaFormat.KEY_BIT_RATE)))
            assertEquals(16, c.getInt(c.getColumnIndexOrThrow(TrackProviderConsts.COLUMN_BITS_PER_SAMPLE)))
            assertEquals("Composer", c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.COMPOSER)))
        }
        // Poweramp's hierarchy scan requests only a subset of the metadata columns.
        provider.queryChildDocuments("al:album", arrayOf(Document.COLUMN_DOCUMENT_ID,
            MediaStore.Audio.AudioColumns.DURATION, TrackProviderConsts.COLUMN_TRACK_ALT), sortOrder = null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("tr:song", c.getString(0))
            assertEquals(212000L, c.getLong(1))
            assertEquals(2005, c.getInt(2))
        }
    }

    @Test fun unknownTagsRemainUnsetAndUnknownDiscDoesNotBecomeDiscOne() {
        val (services, provider) = provider()
        val sparse = client.parseSong(JSONObject("""{"id":"song","albumId":"album","track":5}"""))
        assertEquals(0, sparse.sampleRate)
        assertEquals(0, sparse.channelCount)
        assertEquals("", sparse.composer)
        services.db.applySync(emptyList(), listOf(album), listOf(sparse), emptySet(), null, " · ND")
        provider.queryDocument("tr:song", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(5, c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TRACK)))
            for (column in listOf(MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT,
                MediaFormat.KEY_BIT_RATE, MediaStore.Audio.AudioColumns.COMPOSER)) {
                assertTrue(column, c.isNull(c.getColumnIndexOrThrow(column)))
            }
        }
    }

    @Test fun extendedMetadataChangesRefreshAnOtherwiseUnchangedAlbum() {
        val app: Application = RuntimeEnvironment.getApplication()
        LibraryDb(app).useDatabase { db ->
            var track = song()
            fun sync() = db.applySync(emptyList(), listOf(album), listOf(track), emptySet(), null, " · ND")
            assertEquals(1, sync().added)
            assertEquals(0, sync().changed)
            for (changed in listOf(track.copy(sampleRate = 96000), track.copy(channelCount = 1),
                track.copy(composer = "New composer"), track.copy(bitRate = 800), track.copy(bitDepth = 24))) {
                db.writableDatabase.execSQL("UPDATE songs SET modified = 1")
                val oldHash = track.metaHash
                track = changed
                assertNotEquals(oldHash, track.metaHash)
                assertEquals(1, sync().changed)
                assertEquals(track, db.song(track.id)!!.song)
                assertTrue(db.song(track.id)!!.modified > 1)
                assertEquals(0, sync().changed)
            }
        }
    }

    @Test fun v10UpgradePreservesLibraryAndInvalidatesOldProviderTags() {
        checkLegacyUpgrade(10)
    }

    @Test fun installedV12UpgradePreservesLibraryAndInvalidatesOldProviderTags() {
        checkLegacyUpgrade(12)
    }

    private fun checkLegacyUpgrade(version: Int) {
        val app: Application = RuntimeEnvironment.getApplication()
        val track = song()
        LibraryDb(app).useDatabase { db ->
            db.applySync(emptyList(), listOf(album), listOf(track), emptySet(), null, " · ND")
            db.applyPlaylists(listOf(Playlist("playlist", "Keep me", "", listOf(track.id))), emptyList())
            db.writableDatabase.execSQL("UPDATE songs SET modified = 1, displaySuffix = ' · ND'")
            removeExtendedMetadataColumns(db.writableDatabase)
            db.writableDatabase.version = version
        }
        LibraryDb(app).useDatabase { db ->
            val restored = db.song(track.id)!!
            assertTrue(db.resendTags)
            assertEquals(14, db.readableDatabase.version)
            assertEquals(2, restored.song.disc)
            assertEquals(" · ND", restored.displaySuffix)
            assertTrue(restored.modified > 1)
            assertEquals(0, restored.song.sampleRate)
            assertEquals(0, restored.song.channelCount)
            assertEquals("", restored.song.composer)
            assertEquals(track.id, db.playlistSongs("playlist").single().song.id)
            assertEquals(1, db.applySync(emptyList(), listOf(album), listOf(track), emptySet(), null, " · ND").changed)
            assertEquals(track, db.song(track.id)!!.song)
        }
    }

    @Test fun earlierMetadataBuildKeepsAlreadyPresentColumns() {
        val app: Application = RuntimeEnvironment.getApplication()
        val track = song()
        LibraryDb(app).useDatabase { db ->
            db.applySync(emptyList(), listOf(album), listOf(track), emptySet(), null, " · ND")
            db.writableDatabase.version = 11
        }
        LibraryDb(app).useDatabase { db ->
            assertEquals(track, db.song(track.id)!!.song)
            assertEquals(14, db.readableDatabase.version)
            assertTrue(db.resendTags)
        }
    }
}

/** Build the actual pre-v11 schema; changing user_version alone would leave the new columns behind. */
internal fun removeExtendedMetadataColumns(db: SQLiteDatabase) {
    val columns = db.rawQuery("PRAGMA table_info(songs)", null).use { c ->
        buildList { while (c.moveToNext()) if (c.getString(1) !in setOf("sampleRate", "channelCount", "composer")) add(c.getString(1)) }
    }.joinToString(",")
    val sql = db.rawQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'songs'", null).use { c ->
        c.moveToFirst(); c.getString(0)
    }.replace("CREATE TABLE songs", "CREATE TABLE songs_old")
        .replace(Regex(",\\s*sampleRate INTEGER NOT NULL DEFAULT 0"), "")
        .replace(Regex(",\\s*channelCount INTEGER NOT NULL DEFAULT 0"), "")
        .replace(Regex(",\\s*composer TEXT NOT NULL DEFAULT ''"), "")
    db.execSQL(sql)
    db.execSQL("INSERT INTO songs_old ($columns) SELECT $columns FROM songs")
    db.execSQL("DROP TABLE songs")
    db.execSQL("ALTER TABLE songs_old RENAME TO songs")
    db.execSQL("CREATE INDEX songs_album ON songs(albumId)")
    db.execSQL("CREATE TRIGGER invalidate_deleted_track AFTER DELETE ON songs BEGIN " +
        "DELETE FROM album_match_inputs WHERE albumId = OLD.albumId; END")
}

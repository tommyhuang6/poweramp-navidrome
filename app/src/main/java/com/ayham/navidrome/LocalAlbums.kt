package com.ayham.navidrome

import android.content.Context
import android.database.Cursor
import android.os.Environment
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.TableDefs
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

internal data class TrackTags(
    val album: String, val albumArtist: String, val title: String, val artist: String,
    val disc: Int, val track: Int, val durationMs: Long, val format: String,
)

internal data class LocalTrack(val tags: TrackTags, val path: String)
internal data class AlbumDisplay(val suffix: String = "", val localPaths: Map<String, String> = emptyMap())

/** Match each physical file at most once within a cloud album, including repeated track titles. */
internal class LocalAlbums(tracks: List<LocalTrack>) {
    private val albums = tracks.filter {
        val t = it.tags
        t.album.isNotBlank() && t.artist.isNotBlank() && t.title.isNotBlank() &&
            t.format.isNotBlank() && t.durationMs > 0
    }.groupBy { it.tags.album }
    private val indexes = mutableMapOf<String, Map<TrackTags, List<LocalTrack>>>()
    private val digests = mutableMapOf<String, String>()

    /** Stable across Poweramp sort orders and process restarts. Only hashes the indexed metadata. */
    fun digest(album: String): String {
        val tracks = albums[album] ?: return ""
        return digests.getOrPut(album) {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                fun text(value: String) {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
                for ((t, path) in tracks.sortedBy { it.path }) {
                    text(path)
                    text(t.albumArtist)
                    text(t.title)
                    text(t.artist)
                    text(t.format)
                    out.writeInt(t.disc)
                    out.writeInt(t.track)
                    out.writeLong(t.durationMs)
                }
            }
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()))
        }
    }

    fun display(songs: List<Pair<String, TrackTags>>, suffix: String): AlbumDisplay {
        val matches = mutableMapOf<String, String>()
        val used = mutableSetOf<String>()
        // Shortest first on both sides permits one-to-one matches even when two candidates
        // fall within the same duration window.
        for ((id, tags) in songs.sortedBy { it.second.durationMs }) {
            if (tags.durationMs <= 0) continue
            val album = albums[tags.album] ?: continue
            val index = indexes.getOrPut(tags.album) {
                album.sortedWith(compareBy<LocalTrack> { it.tags.durationMs }.thenBy { it.path })
                    .groupBy { it.tags.copy(durationMs = 0) }
            }
            val match = index[tags.copy(durationMs = 0)]?.firstOrNull {
                kotlin.math.abs(it.tags.durationMs - tags.durationMs) <= 2000 && it.path !in used
            } ?: continue
            used += match.path
            matches[id] = match.path
        }
        return when {
            matches.isEmpty() -> AlbumDisplay()
            matches.size == songs.size -> AlbumDisplay(localPaths = matches)
            else -> AlbumDisplay(suffix = suffix)
        }
    }
}

/** Read Poweramp's selected music folders, including SD cards, without opening any audio files. */
internal fun readLocalAlbums(ctx: Context): LocalAlbums {
    fun query(category: String, vararg columns: String): Cursor =
        ctx.contentResolver.query(PowerampAPI.ROOT_URI.buildUpon().appendPath(category).build(), columns, null, null, null)
            ?: throw IOException("Poweramp did not return its library. Open Poweramp and grant library access.")

    fun names(category: String, id: String, name: String) = query(category, id, name).use { c ->
        buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1).orEmpty()) }
    }

    val albumNames = names("albums", TableDefs.Albums._ID, TableDefs.Albums.ALBUM)
    val artistNames = names("artists", TableDefs.Artists._ID, TableDefs.Artists.ARTIST)
    val albumArtists = names("album_artists", TableDefs.AlbumArtists._ID, TableDefs.AlbumArtists.ALBUM_ARTIST)
    val folders = names("folders", TableDefs.Folders._ID, TableDefs.Folders.PATH)
    val f = TableDefs.Files
    val tracks = query("files", f.ALBUM_ID, f.ALBUM_ARTIST_ID, f.TITLE_TAG, f.ARTIST_ID, f.DISC, f.TRACK_TAG,
        f.DURATION, f.FILE_PATH, f.FOLDER_ID, f.NAME, f.URL, f.FILE_TYPE).use { c ->
        buildList {
            while (c.moveToNext()) {
                if (!c.getString(10).isNullOrEmpty() || c.getInt(11) == PowerampAPI.Track.FileType.TYPE_STREAM) continue
                val path = c.getString(7) ?: ((folders[c.getLong(8)] ?: continue) + c.getString(9))
                val file = when {
                    path.startsWith("/") -> path
                    path.startsWith("primary/") -> Environment.getExternalStorageDirectory().path + "/" + path.substringAfter('/')
                    sdCardPath.matches(path) -> "/storage/$path"
                    else -> continue // @authority/... provider, stream, or unknown storage
                }
                if ('\n' in file || '\r' in file) continue // cannot be represented in M3U
                val tags = TrackTags(albumNames[c.getLong(0)].orEmpty(), albumArtists[c.getLong(1)].orEmpty(),
                    c.getString(2).orEmpty(), artistNames[c.getLong(3)].orEmpty(), c.getInt(4), c.getInt(5),
                    c.getLong(6), file.substringAfterLast('/').substringAfterLast('.', "").lowercase())
                add(LocalTrack(tags, file))
            }
        }
    }
    return LocalAlbums(tracks)
}

private val sdCardPath = Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}/.+")

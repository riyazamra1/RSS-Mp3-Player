package com.riyaz.rssmp3player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val folder: String,
    val durationMs: Long,
    val uri: Uri,
) {
    val artworkUri: Uri?
        get() = if (albumId > 0L) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
}

object MusicRepository {
    fun load(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, null, sort
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val folderCol = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val rawArtist = c.getString(artistCol)
                val rawAlbum = c.getString(albumCol)
                val path = if (folderCol >= 0) c.getString(folderCol).orEmpty() else ""
                songs += Song(
                    id = id,
                    title = c.getString(titleCol) ?: "Unknown title",
                    artist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") "Unknown artist" else rawArtist,
                    album = if (rawAlbum.isNullOrBlank() || rawAlbum == "<unknown>") "Unknown album" else rawAlbum,
                    albumId = if (albumIdCol >= 0) c.getLong(albumIdCol) else 0L,
                    folder = path.trimEnd('/').ifBlank { "Music / Other" },
                    durationMs = if (durationCol >= 0) c.getLong(durationCol) else 0L,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                )
            }
        }
        return songs
    }
}

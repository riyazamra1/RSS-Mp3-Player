package com.riyaz.rssmp3player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import java.io.File
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlayerExtras(
    player: MediaController, songs: List<Song>, prefs: android.content.SharedPreferences,
    showQueue: Boolean, onQueueDismiss: () -> Unit, showNowPlaying: Boolean, onNowPlayingDismiss: () -> Unit,
    showSleepTimer: Boolean, onSleepTimerDismiss: () -> Unit, actionSong: Song?, onActionSongDismiss: () -> Unit,
    propertiesSong: Song?, onPropertiesDismiss: () -> Unit, favourites: Set<String>,
    onToggleFavourite: (String) -> Unit, onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    if (showQueue) AlertDialog(onDismissRequest = onQueueDismiss, title = { Text("Play queue (${player.mediaItemCount})") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (player.mediaItemCount == 0) Text("Queue is empty.")
            repeat(player.mediaItemCount) { i ->
                val item = player.getMediaItemAt(i)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { player.seekTo(i, 0L); player.play() }) {
                        Text(item.mediaMetadata.title?.toString() ?: "Unknown track", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.mediaMetadata.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { if (i > 0) player.moveMediaItem(i, i - 1) }) { Text("↑") }
                    TextButton(onClick = { if (i < player.mediaItemCount - 1) player.moveMediaItem(i, i + 1) }) { Text("↓") }
                    TextButton(onClick = { player.removeMediaItem(i) }) { Text("×") }
                }
            }
        } }, confirmButton = { TextButton(onClick = onQueueDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { player.clearMediaItems(); onQueueDismiss() }) { Text("Clear queue") } })

    if (showNowPlaying) {
        val active = songs.firstOrNull { it.id.toString() == player.currentMediaItem?.mediaId }
        Dialog(onDismissRequest = onNowPlayingDismiss) {
            Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("NOW PLAYING", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onNowPlayingDismiss(); onMessage("Open the Queue button in the player to manage queued tracks.") }) { Text("Queue") }
                    }
                    AlbumCover(active, Modifier.fillMaxWidth().height(250.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(player.mediaMetadata.title?.toString() ?: "Choose a song", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(player.mediaMetadata.artist?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth().pointerInput(player.currentMediaItemIndex) {
                        var distance = 0f
                        detectHorizontalDragGestures(onDragEnd = {
                            if (distance < -70f) player.seekToNextMediaItem()
                            if (distance > 70f) player.seekToPreviousMediaItem()
                            distance = 0f
                        }, onHorizontalDrag = { change, amount -> change.consume(); distance += amount })
                    }, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { player.seekToPreviousMediaItem() }) { Text("⏮ Previous") }
                        Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Text(if (player.isPlaying) "Ⅱ Pause" else "▶ Play") }
                        TextButton(onClick = { player.seekToNextMediaItem() }) { Text("Next ⏭") }
                    }
                    Text("Swipe left or right to change tracks", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (showSleepTimer) {
        var minutes by remember { mutableIntStateOf(30) }
        AlertDialog(onDismissRequest = onSleepTimerDismiss, title = { Text("Sleep timer / auto-close") },
            text = { Column {
                Text("Stop playback and clear the player after:")
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { minutes = m }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = minutes == m, onClick = { minutes = m }); Text("$m minutes")
                    }
                }
            } },
            confirmButton = { TextButton(onClick = {
                context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_SLEEP_TIMER).putExtra(PlaybackService.EXTRA_SLEEP_MINUTES, minutes))
                onSleepTimerDismiss(); onMessage("Sleep timer set for $minutes minutes.")
            }) { Text("Start timer") } },
            dismissButton = { Row {
                TextButton(onClick = { context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_SLEEP_TRACK)); onSleepTimerDismiss(); onMessage("Playback will stop after this song.") }) { Text("End of song") }
                TextButton(onClick = { context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_SLEEP_QUEUE)); onSleepTimerDismiss(); onMessage("Playback will stop after the queue.") }) { Text("End of queue") }
            } })
    }

    var trimSong by remember { mutableStateOf<Song?>(null) }
    actionSong?.let { song ->
        AlertDialog(onDismissRequest = onActionSongDismiss, title = { Text(song.title) }, text = { Text("Track actions") },
            confirmButton = { Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = { val i = songs.indexOfFirst { it.id == song.id }; if (i >= 0) player.addMediaItem(player.mediaItemCount, MediaItemFactory.fromSong(song)); onActionSongDismiss(); onMessage("Added to queue.") }, modifier = Modifier.fillMaxWidth()) { Text("Add to queue") }
                TextButton(onClick = { val i = songs.indexOfFirst { it.id == song.id }; if (i >= 0) player.addMediaItem((player.currentMediaItemIndex + 1).coerceAtLeast(0), MediaItemFactory.fromSong(song)); onActionSongDismiss(); onMessage("Added to play next.") }, modifier = Modifier.fillMaxWidth()) { Text("Play next") }
                TextButton(onClick = { onActionSongDismiss(); onMessage("Properties: ${song.title}\nArtist: ${song.artist}\nAlbum: ${song.album}\nFolder: ${song.folder}\nDuration: ${song.durationMs} ms\nURI: ${song.uri}") }, modifier = Modifier.fillMaxWidth()) { Text("Properties") }
                TextButton(onClick = {
                    try {
                        val values = ContentValues().apply { put(MediaStore.Audio.Media.TITLE, song.title + " (edited)") }
                        val changed = context.contentResolver.update(song.uri, values, null, null)
                        onMessage(if (changed > 0) "Media title updated." else "Android did not confirm media write access; no change was confirmed.")
                    } catch (e: Exception) { onMessage("Android requires write approval before editing tags: ${e.message}") }
                    onActionSongDismiss()
                }, modifier = Modifier.fillMaxWidth()) { Text("Edit tags") }
                TextButton(onClick = { trimSong = song; onActionSongDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Trim audio / set ringtone") }
                TextButton(onClick = { onActionSongDismiss(); onMessage("Use Properties and a file manager to rename this file. Android may require write approval.") }, modifier = Modifier.fillMaxWidth()) { Text("Rename file") }
                TextButton(onClick = {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context)) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
                            onMessage("Allow modify system settings, then select Set as ringtone again.")
                        } else {
                            RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, song.uri)
                            onMessage("Default ringtone updated.")
                        }
                    } catch (e: Exception) { onMessage("Could not set ringtone: ${e.message}") }
                    onActionSongDismiss()
                }, modifier = Modifier.fillMaxWidth()) { Text("Set as ringtone") }
                TextButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_SEND).apply { type = "audio/*"; putExtra(Intent.EXTRA_STREAM, song.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } catch (_: Exception) { onMessage("Unable to share track.") }; onActionSongDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Share") }
                TextButton(onClick = { onToggleFavourite(song.id.toString()); onActionSongDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(if (favourites.contains(song.id.toString())) "Remove favourite" else "Add to favourites") }
                TextButton(onClick = { onActionSongDismiss(); onMessage("Delete requires an Android media-delete approval. No file was deleted.") }, modifier = Modifier.fillMaxWidth()) { Text("Delete (approval required)") }
            } }, dismissButton = { TextButton(onClick = onActionSongDismiss) { Text("Cancel") } })
    }

    trimSong?.let { song -> AudioTrimDialog(song = song, onDismiss = { trimSong = null }, onMessage = onMessage) }

    propertiesSong?.let { song -> AlertDialog(onDismissRequest = onPropertiesDismiss, title = { Text("Track properties") },
        text = { Column { Text("Title: ${song.title}"); Text("Artist: ${song.artist}"); Text("Album: ${song.album}"); Text("Folder: ${song.folder}"); Text("Duration: ${song.durationMs} ms"); Text("URI: ${song.uri}") } },
        confirmButton = { TextButton(onClick = onPropertiesDismiss) { Text("Close") } })
    }
}

private object MediaItemFactory {
    fun fromSong(song: Song): androidx.media3.common.MediaItem =
        androidx.media3.common.MediaItem.Builder().setMediaId(song.id.toString()).setUri(song.uri)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).setArtworkUri(song.artworkUri).build()).build()
}

@Composable
private fun AlbumCover(song: Song?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, song?.artworkUri) {
        value = withContext(Dispatchers.IO) {
            try { song?.artworkUri?.let { context.contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream) } } }
            catch (_: Exception) { null }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = "Album artwork", modifier = Modifier.fillMaxSize())
        else Text("♫", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
    }
}


@Composable
private fun AudioTrimDialog(song: Song, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val durationSeconds = (song.durationMs / 1000f).coerceAtLeast(1f)
    var startSeconds by remember { mutableFloatStateOf(0f) }
    var endSeconds by remember { mutableFloatStateOf(durationSeconds) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Trim audio") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Start: ${startSeconds.toInt()} sec")
            Slider(value = startSeconds, onValueChange = { startSeconds = it.coerceAtMost((endSeconds - 1).coerceAtLeast(0f)) }, valueRange = 0f..(durationSeconds - 1f).coerceAtLeast(0f))
            Text("End: ${endSeconds.toInt()} sec")
            Slider(value = endSeconds, onValueChange = { endSeconds = it.coerceAtLeast(startSeconds + 1f) }, valueRange = 1f..durationSeconds)
            Text("Exports a trimmed M4A copy. The original file remains unchanged.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(enabled = !busy && endSeconds > startSeconds, onClick = {
            busy = true
            val output = File(context.cacheDir, "rss_trim_${System.currentTimeMillis()}.m4a")
            val clipped = MediaItem.Builder().setUri(song.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs((startSeconds * 1000).toLong()).setEndPositionMs((endSeconds * 1000).toLong()).build()
            ).build()
            val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, result: ExportResult) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, "RSS Trim - ${song.title}.m4a")
                            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/RSS MP3 Player")
                            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri == null) onMessage("Trim finished, but Android could not create the output file.")
                        else {
                            context.contentResolver.openOutputStream(uri)?.use { sink -> output.inputStream().use { it.copyTo(sink) } }
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context)) {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
                                    onMessage("Trim saved. Allow modify system settings, then choose the saved audio as ringtone.")
                                } else {
                                    RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, uri)
                                    onMessage("Trimmed audio saved and set as ringtone.")
                                }
                            } catch (x: Exception) { onMessage("Trim saved; ringtone permission required: ${x.message}") }
                        }
                    } catch (x: Exception) { onMessage("Could not save trimmed audio: ${x.message}") }
                    output.delete(); busy = false; onDismiss()
                }
                override fun onError(composition: androidx.media3.transformer.Composition, result: ExportResult, exception: ExportException) {
                    output.delete(); busy = false
                    onMessage("Audio trim failed: ${exception.message ?: "unsupported audio format"}")
                }
            }).build()
            try { transformer.start(clipped, output.absolutePath) }
            catch (x: Exception) { busy = false; output.delete(); onMessage("Could not start trim: ${x.message}") }
        }) { Text(if (busy) "Trimming…" else "Trim + set ringtone") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}

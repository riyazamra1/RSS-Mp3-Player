package com.riyaz.rssmp3player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        var nowPosition by remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
        var nowDuration by remember(player) { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
        var seeking by remember { mutableStateOf(false) }
        var seekValue by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(player, showNowPlaying) {
            while (showNowPlaying) {
                nowPosition = player.currentPosition.coerceAtLeast(0L)
                nowDuration = player.duration.coerceAtLeast(0L)
                kotlinx.coroutines.delay(350)
            }
        }
        Dialog(
            onDismissRequest = onNowPlayingDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onNowPlayingDismiss) { Text("⌄  MINIMIZE") }
                        Spacer(Modifier.weight(1f))
                        Text("NOW PLAYING", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onNowPlayingDismiss(); onMessage("Open Queue to manage queued tracks.") }) { Text("QUEUE") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.weight(1f).fillMaxWidth().widthIn(max = 460.dp), contentAlignment = Alignment.Center) {
                        AlbumCover(active, Modifier.fillMaxSize().padding(4.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(player.mediaMetadata.title?.toString() ?: "Choose a song", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(player.mediaMetadata.artist?.toString().orEmpty().ifBlank { "Unknown artist" },
                                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                        val bars = 48
                        val gap = 3.dp.toPx()
                        val bw = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(1f)
                        for (i in 0 until bars) {
                            val wave = (kotlin.math.abs(kotlin.math.sin(i * 0.53)) * 0.68 + 0.16).toFloat()
                            val h = size.height * wave
                            drawRoundRect(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = if (player.isPlaying) 0.95f else 0.42f),
                                topLeft = Offset(i * (bw + gap), (size.height - h) / 2f),
                                size = Size(bw, h),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2f)
                            )
                        }
                    }
                    val maxPosition = nowDuration.coerceAtLeast(1L).toFloat()
                    Slider(
                        value = if (seeking) seekValue.coerceIn(0f, maxPosition) else nowPosition.toFloat().coerceIn(0f, maxPosition),
                        onValueChange = { seeking = true; seekValue = it },
                        onValueChangeFinished = { player.seekTo(seekValue.toLong()); seeking = false },
                        valueRange = 0f..maxPosition
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTrackTime(if (seeking) seekValue.toLong() else nowPosition), style = MaterialTheme.typography.labelSmall)
                        Text(formatTrackTime(nowDuration), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { player.shuffleModeEnabled = !player.shuffleModeEnabled }) {
                            Text(if (player.shuffleModeEnabled) "🔀" else "⇄", style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(onClick = { player.seekToPreviousMediaItem() }) { Text("⏮", style = MaterialTheme.typography.headlineMedium) }
                        FilledIconButton(onClick = { if (player.isPlaying) player.pause() else player.play() },
                            modifier = Modifier.size(72.dp)) {
                            Text(if (player.isPlaying) "Ⅱ" else "▶", style = MaterialTheme.typography.headlineMedium)
                        }
                        IconButton(onClick = { player.seekToNextMediaItem() }) { Text("⏭", style = MaterialTheme.typography.headlineMedium) }
                        IconButton(onClick = { player.repeatMode = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        } }) { Text(if (player.repeatMode == Player.REPEAT_MODE_ONE) "🔂" else "🔁", style = MaterialTheme.typography.titleLarge) }
                    }
                    Spacer(Modifier.height(20.dp))
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


private fun formatTrackTime(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0L) / 60000, (ms.coerceAtLeast(0L) / 1000) % 60)

package com.example.mp3player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private val controller = mutableStateOf<MediaController?>(null)
    private val songs = mutableStateOf<List<Song>>(emptyList())
    private val granted = mutableStateOf(false)

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private fun refresh() {
        val ok = ContextCompat.checkSelfPermission(this, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        granted.value = ok
        if (ok) songs.value = MusicRepository.load(this)
    }

    private fun requestPermissions() {
        val perms = mutableListOf(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    if (!granted.value) {
                        PermissionScreen(onGrant = { requestPermissions() })
                    } else {
                        PlayerScreen(controller.value, songs.value)
                    }
                }
            }
        }
        refresh()
        if (!granted.value) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({ controller.value = f.get() }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller.value = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Allow access to your music", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("Grant permission") }
    }
}

@Composable
fun PlayerScreen(player: MediaController?, songs: List<Song>) {
    if (player == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        PlayerContent(player, songs)
    }
}

private fun fmt(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
fun PlayerContent(player: MediaController, songs: List<Song>) {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var title by remember { mutableStateOf(player.mediaMetadata.title?.toString() ?: "Nothing playing") }
    var artist by remember { mutableStateOf(player.mediaMetadata.artist?.toString() ?: "") }
    var currentId by remember { mutableStateOf(player.currentMediaItem?.mediaId) }
    var shuffle by remember { mutableStateOf(player.shuffleModeEnabled) }
    var repeat by remember { mutableIntStateOf(player.repeatMode) }
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var query by remember { mutableStateOf("") }

    val mediaItems = remember(songs) {
        songs.map { s ->
            MediaItem.Builder()
                .setMediaId(s.id.toString())
                .setUri(s.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).build()
                )
                .build()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaMetadataChanged(md: MediaMetadata) {
                title = md.title?.toString() ?: "Nothing playing"
                artist = md.artist?.toString() ?: ""
                currentId = player.currentMediaItem?.mediaId
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
            override fun onRepeatModeChanged(mode: Int) { repeat = mode }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            currentId = player.currentMediaItem?.mediaId
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Text(
            "My MP3 Player",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search songs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )

        val visible = songs.withIndex().filter { (_, s) ->
            query.isBlank() ||
                s.title.contains(query, ignoreCase = true) ||
                s.artist.contains(query, ignoreCase = true)
        }

        if (songs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No music found on this phone")
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.value.id }) { (index, song) ->
                    val selected = currentId == song.id.toString()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                player.setMediaItems(mediaItems, index, 0L)
                                player.prepare()
                                player.play()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            song.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            song.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)

            val maxVal = duration.coerceAtLeast(1L).toFloat()
            Slider(
                value = if (dragging) dragValue else position.toFloat().coerceIn(0f, maxVal),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    player.seekTo(dragValue.toLong())
                    dragging = false
                },
                valueRange = 0f..maxVal
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(if (dragging) dragValue.toLong() else position), style = MaterialTheme.typography.bodySmall)
                Text(fmt(duration), style = MaterialTheme.typography.bodySmall)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { player.shuffleModeEnabled = !shuffle }) {
                    Text(if (shuffle) "Shuffle: On" else "Shuffle: Off")
                }
                Button(onClick = { player.seekToPreviousMediaItem() }) { Text("Prev") }
                Button(onClick = { if (isPlaying) player.pause() else player.play() }) {
                    Text(if (isPlaying) "Pause" else "Play")
                }
                Button(onClick = { player.seekToNextMediaItem() }) { Text("Next") }
                TextButton(onClick = {
                    player.repeatMode = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Text(
                        when (repeat) {
                            Player.REPEAT_MODE_ALL -> "Repeat: All"
                            Player.REPEAT_MODE_ONE -> "Repeat: One"
                            else -> "Repeat: Off"
                        }
                    )
                }
            }
        }
    }
}

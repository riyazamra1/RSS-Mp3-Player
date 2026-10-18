package com.example.mp3player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import kotlin.math.abs
import kotlin.math.max

private const val PREFS = "rss_mp3_player"
private const val FAVOURITES = "favourites"

class MainActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private val controller = mutableStateOf<MediaController?>(null)
    private val songs = mutableStateOf<List<Song>>(emptyList())
    private val granted = mutableStateOf(false)
    private val recordGranted = mutableStateOf(false)

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val recordLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            recordGranted.value = it
        }

    private fun refresh() {
        val ok = ContextCompat.checkSelfPermission(this, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        granted.value = ok
        recordGranted.value = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) songs.value = MusicRepository.load(this)
    }

    private fun requestPermissions() {
        val perms = mutableListOf(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            var darkTheme by remember {
                mutableStateOf(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dark_theme", true))
            }
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    if (!granted.value) {
                        PermissionScreen(onGrant = { requestPermissions() })
                    } else {
                        PlayerScreen(
                            player = controller.value,
                            songs = songs.value,
                            darkTheme = darkTheme,
                            onDarkThemeChange = {
                                darkTheme = it
                                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("dark_theme", it).apply()
                            },
                            recordGranted = recordGranted.value,
                            onRequestRecordPermission = { recordLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        )
                    }
                }
            }
        }
        if (!granted.value) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            try {
                controller.value = f.get()
            } catch (_: Exception) {
                controller.value = null
            }
        }, ContextCompat.getMainExecutor(this))
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
        Text("RSS MP3 Player only needs music access to list and play audio files on this device.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("Grant permission") }
    }
}

@Composable
fun PlayerScreen(
    player: MediaController?,
    songs: List<Song>,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    recordGranted: Boolean,
    onRequestRecordPermission: () -> Unit
) {
    if (player == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        PlayerContent(player, songs, darkTheme, onDarkThemeChange, recordGranted, onRequestRecordPermission)
    }
}

private fun fmt(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0L) / 60000, (ms.coerceAtLeast(0L) / 1000) % 60)

@Composable
fun PlayerContent(
    player: MediaController,
    songs: List<Song>,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    recordGranted: Boolean,
    onRequestRecordPermission: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val audioSessionId = remember(player) { prefs.getInt("audio_session_id", 0) }
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
    var tab by remember { mutableIntStateOf(0) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(prefs.getString("sort", "Title A-Z") ?: "Title A-Z") }
    var favourites by remember { mutableStateOf(
        prefs.getStringSet(FAVOURITES, emptySet())?.toSet() ?: emptySet()
    ) }
    var bassLevel by remember { mutableFloatStateOf(prefs.getInt("bass", 35).toFloat()) }
    var eqLevels by remember { mutableStateOf(
        listOf(0f, 0f, 0f, 0f, 0f)
    ) }
    var visualizerEnabled by remember { mutableStateOf(prefs.getBoolean("visualizer", true)) }
    val visualizerBars = remember { mutableStateListOf<Float>().apply { repeat(36) { add(0.04f) } } }

    val mediaItems = remember(songs) {
        songs.map { s ->
            MediaItem.Builder()
                .setMediaId(s.id.toString())
                .setUri(s.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .build()
                ).build()
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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                title = mediaItem?.mediaMetadata?.title?.toString() ?: "Nothing playing"
                artist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                currentId = mediaItem?.mediaId
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
            delay(400)
        }
    }

    val equalizer = remember(audioSessionId) {
        try { if (audioSessionId > 0) Equalizer(0, audioSessionId).apply { enabled = true } else null }
        catch (_: Exception) { null }
    }
    val bassBoost = remember(audioSessionId) {
        try { if (audioSessionId > 0) BassBoost(0, audioSessionId).apply { enabled = true } else null }
        catch (_: Exception) { null }
    }
    DisposableEffect(equalizer, bassBoost) {
        onDispose {
            try { equalizer?.release() } catch (_: Exception) {}
            try { bassBoost?.release() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(visualizerEnabled, recordGranted, audioSessionId, isPlaying) {
        if (!visualizerEnabled || !recordGranted || audioSessionId <= 0) {
            while (visualizerEnabled && isPlaying && !recordGranted) {
                for (i in visualizerBars.indices) {
                    visualizerBars[i] = (0.12f + ((i * 13 + System.nanoTime() / 8_000_000L) % 70) / 100f).coerceIn(0.08f, 0.9f)
                }
                delay(100)
            }
            return@LaunchedEffect
        }
        var visualizer: Visualizer? = null
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) {
                        val step = max(1, waveform.size / visualizerBars.size)
                        for (i in visualizerBars.indices) {
                            val raw = waveform[(i * step).coerceAtMost(waveform.lastIndex)].toInt() and 0xff
                            visualizerBars[i] = (abs(raw - 128) / 128f).coerceIn(0.05f, 1f)
                        }
                    }
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) = Unit
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
            while (visualizerEnabled && isPlaying) delay(150)
        } catch (_: Exception) {
            // The animated fallback remains available if device audio capture is restricted.
        } finally {
            try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        }
    }

    val sortedSongs = remember(songs, sortMode) {
        when (sortMode) {
            "Title Z-A" -> songs.sortedByDescending { it.title.lowercase() }
            "Artist" -> songs.sortedBy { it.artist.lowercase() }
            "Album" -> songs.sortedBy { it.album.lowercase() }
            "Longest first" -> songs.sortedByDescending { it.durationMs }
            else -> songs.sortedBy { it.title.lowercase() }
        }
    }
    val folders = remember(songs) { songs.map { it.folder }.distinct().sorted() }
    val albums = remember(songs) { songs.map { it.album }.distinct().sorted() }
    val visibleSongs = remember(sortedSongs, query, selectedFolder, selectedAlbum, favourites, tab) {
        sortedSongs.filter { song ->
            val matchesQuery = query.isBlank() ||
                song.title.contains(query, true) || song.artist.contains(query, true) ||
                song.album.contains(query, true) || song.folder.contains(query, true)
            val matchesFolder = tab != 1 || selectedFolder == null || song.folder == selectedFolder
            val matchesAlbum = tab != 2 || selectedAlbum == null || song.album == selectedAlbum
            val matchesFavourite = tab != 6 || favourites.contains(song.id.toString())
            matchesQuery && matchesFolder && matchesAlbum && matchesFavourite
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("RSS MP3 PLAYER", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text("${songs.size} tracks on this device", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { tab = 5 }) { Text("⚙ Settings") }
        }

        val tabs = listOf("Songs", "Folders", "Albums", "Visualiser", "Equalizer", "Settings", "Favourites")
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }

        when (tab) {
            3 -> VisualizerScreen(
                title = title, artist = artist, bars = visualizerBars,
                playing = isPlaying, enabled = visualizerEnabled,
                onToggle = {
                    visualizerEnabled = it
                    prefs.edit().putBoolean("visualizer", it).apply()
                },
                recordGranted = recordGranted,
                onRequestRecordPermission = onRequestRecordPermission
            )
            4 -> EqualizerScreen(
                equalizer = equalizer,
                bassBoost = bassBoost,
                bassLevel = bassLevel,
                onBassChange = {
                    bassLevel = it
                    prefs.edit().putInt("bass", it.toInt()).apply()
                    try { bassBoost?.setStrength((it * 10).toInt().toShort()) } catch (_: Exception) {}
                },
                eqLevels = eqLevels,
                onEqChange = { band, value ->
                    val updated = eqLevels.toMutableList()
                    if (band in updated.indices) updated[band] = value
                    eqLevels = updated
                    try { equalizer?.setBandLevel(band.toShort(), (value * 100).toInt().toShort()) } catch (_: Exception) {}
                }
            )
            5 -> SettingsScreen(
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
                sortMode = sortMode,
                onSortChange = {
                    sortMode = it
                    prefs.edit().putString("sort", it).apply()
                },
                onRescan = { /* list is refreshed when permission state changes; the button explains how to refresh */ }
            )
            else -> {
                if (tab == 1 && selectedFolder != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedFolder = null }) { Text("← All folders") }
                        Text(selectedFolder ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (tab == 2 && selectedAlbum != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedAlbum = null }) { Text("← All albums") }
                        Text(selectedAlbum ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (tab == 1 && selectedFolder == null) {
                    LazyColumn(Modifier.weight(1f)) {
                        items(folders, key = { it }) { folder ->
                            ListTile(title = folder, subtitle = "${songs.count { it.folder == folder }} tracks", leading = "📁") {
                                selectedFolder = folder
                            }
                        }
                    }
                } else if (tab == 2 && selectedAlbum == null) {
                    LazyColumn(Modifier.weight(1f)) {
                        items(albums, key = { it }) { album ->
                            ListTile(title = album, subtitle = "${songs.count { it.album == album }} tracks", leading = "💿") {
                                selectedAlbum = album
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search title, artist, album or folder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${visibleSongs.size} tracks", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            sortMode = when (sortMode) {
                                "Title A-Z" -> "Title Z-A"
                                "Title Z-A" -> "Artist"
                                "Artist" -> "Album"
                                "Album" -> "Longest first"
                                else -> "Title A-Z"
                            }
                            prefs.edit().putString("sort", sortMode).apply()
                        }) { Text("Sort: $sortMode") }
                    }
                    if (visibleSongs.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(if (songs.isEmpty()) "No music found. Add audio files to your device." else "No matching tracks.")
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(visibleSongs, key = { it.id }) { song ->
                                val selected = currentId == song.id.toString()
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        val index = songs.indexOfFirst { it.id == song.id }
                                        if (index >= 0) {
                                            player.setMediaItems(mediaItems, index, 0L)
                                            player.prepare()
                                            player.play()
                                        }
                                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        Text("${song.artist} • ${song.album}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall)
                                        Text("${song.folder}  •  ${fmt(song.durationMs)}", maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = {
                                        val id = song.id.toString()
                                        favourites = if (favourites.contains(id)) favourites - id else favourites + id
                                        prefs.edit().putStringSet(FAVOURITES, favourites).apply()
                                    }) { Text(if (favourites.contains(song.id.toString())) "♥" else "♡") }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        MiniPlayer(
            player = player, title = title, artist = artist, playing = isPlaying,
            position = position, duration = duration, dragging = dragging, dragValue = dragValue,
            onDragStart = { dragging = true; dragValue = position.toFloat() },
            onDragValue = { dragging = true; dragValue = it },
            onSeek = { player.seekTo(it); dragging = false },
            onPrevious = { player.seekToPreviousMediaItem() },
            onTogglePlay = { if (isPlaying) player.pause() else player.play() },
            onNext = { player.seekToNextMediaItem() },
            shuffle = shuffle, onShuffle = { player.shuffleModeEnabled = !shuffle },
            repeat = repeat, onRepeat = {
                player.repeatMode = when (repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        )
    }
}

@Composable
private fun ListTile(title: String, subtitle: String, leading: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(leading, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun MiniPlayer(
    player: MediaController, title: String, artist: String, playing: Boolean,
    position: Long, duration: Long, dragging: Boolean, dragValue: Float,
    onDragStart: () -> Unit, onDragValue: (Float) -> Unit, onSeek: (Long) -> Unit,
    onPrevious: () -> Unit, onTogglePlay: () -> Unit, onNext: () -> Unit,
    shuffle: Boolean, onShuffle: () -> Unit, repeat: Int, onRepeat: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
        Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        val maxVal = duration.coerceAtLeast(1L).toFloat()
        Slider(
            value = if (dragging) dragValue.coerceIn(0f, maxVal) else position.toFloat().coerceIn(0f, maxVal),
            onValueChange = { onDragStart(); onDragValue(it) },
            onValueChangeFinished = { onSeek(dragValue.toLong()) },
            valueRange = 0f..maxVal
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(if (dragging) dragValue.toLong() else position), style = MaterialTheme.typography.labelSmall)
            Text(fmt(duration), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onShuffle) { Text(if (shuffle) "🔀 On" else "🔀") }
            TextButton(onClick = onPrevious) { Text("⏮") }
            Button(onClick = onTogglePlay) { Text(if (playing) "Ⅱ Pause" else "▶ Play") }
            TextButton(onClick = onNext) { Text("⏭") }
            TextButton(onClick = onRepeat) { Text(when (repeat) {
                Player.REPEAT_MODE_ALL -> "🔁 All"
                Player.REPEAT_MODE_ONE -> "🔂 One"
                else -> "🔁"
            }) }
        }
    }
}

@Composable
private fun VisualizerScreen(
    title: String, artist: String, bars: SnapshotStateList<Float>, playing: Boolean,
    enabled: Boolean, onToggle: (Boolean) -> Unit, recordGranted: Boolean,
    onRequestRecordPermission: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Text("AUDIO VISUALIZER", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(artist, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(28.dp))
        val visualizerColor = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(190.dp)) {
            val gap = 4.dp.toPx()
            val barWidth = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
            bars.forEachIndexed { index, level ->
                val h = (size.height * level.coerceIn(0.04f, 1f)).coerceAtLeast(4f)
                drawRoundRect(
                    color = if (playing) visualizerColor else Color.Gray,
                    topLeft = Offset(index * (barWidth + gap), (size.height - h) / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(if (recordGranted) "Live audio waveform" else "Animated visualizer preview")
        Spacer(Modifier.height(8.dp))
        Text("Audio capture permission is used only to read playback waveform data for this visualizer.",
            style = MaterialTheme.typography.bodySmall)
        if (!recordGranted) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRequestRecordPermission) { Text("Enable live visualizer") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Visualizer")
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun EqualizerScreen(
    equalizer: Equalizer?,
    bassBoost: BassBoost?,
    bassLevel: Float,
    onBassChange: (Float) -> Unit,
    eqLevels: List<Float>,
    onEqChange: (Int, Float) -> Unit
) {
    var enabled by remember(equalizer) { mutableStateOf(equalizer?.enabled == true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("SOUND & EQUALIZER", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Adjust bass and five equalizer bands for the current playback session.",
            style = MaterialTheme.typography.bodySmall)
        if (equalizer == null || bassBoost == null) {
            Spacer(Modifier.height(20.dp))
            Text("Sound effects are not supported by this device or the audio session is not ready. Start a track and reopen this screen.")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Equalizer")
                Spacer(Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    try { equalizer.enabled = it } catch (_: Exception) {}
                })
            }
            val range = equalizer.bandLevelRange
            val minDb = range[0].toFloat() / 100f
            val maxDb = range[1].toFloat() / 100f
            val bandCount = equalizer.numberOfBands.toInt().coerceAtMost(5)
            repeat(bandCount) { band ->
                val frequency = try { equalizer.getCenterFreq(band.toShort()) / 1000 } catch (_: Exception) { 0 }
                val level = eqLevels.getOrElse(band) {
                    try { equalizer.getBandLevel(band.toShort()).toFloat() / 100f } catch (_: Exception) { 0f }
                }
                Text(if (frequency >= 1000) "${frequency / 1000f} kHz" else "${frequency} Hz",
                    style = MaterialTheme.typography.labelLarge)
                Slider(value = level.coerceIn(minDb, maxDb), onValueChange = { onEqChange(band, it) },
                    valueRange = minDb..maxDb, enabled = enabled)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${minDb} dB", style = MaterialTheme.typography.labelSmall)
                    Text("${level} dB", style = MaterialTheme.typography.labelSmall)
                    Text("+${maxDb} dB", style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Bass boost: ${bassLevel.toInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(value = bassLevel, onValueChange = onBassChange, valueRange = 0f..100f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Off", style = MaterialTheme.typography.labelSmall)
                Text("Maximum", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = {
                repeat(bandCount) { band -> onEqChange(band, 0f) }
                onBassChange(0f)
            }, modifier = Modifier.fillMaxWidth()) { Text("Reset sound settings") }
        }
    }
}

@Composable
private fun SettingsScreen(
    darkTheme: Boolean, onDarkThemeChange: (Boolean) -> Unit,
    sortMode: String, onSortChange: (String) -> Unit, onRescan: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dark theme", fontWeight = FontWeight.SemiBold)
                Text("Use a dark or light player appearance", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Default sort order", fontWeight = FontWeight.SemiBold)
        val sortOptions = listOf("Title A-Z", "Title Z-A", "Artist", "Album", "Longest first")
        sortOptions.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable { onSortChange(option) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = sortMode == option, onClick = { onSortChange(option) })
                Spacer(Modifier.width(8.dp))
                Text(option)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Library", fontWeight = FontWeight.SemiBold)
        Text("The player reads local audio files through Android MediaStore. New files appear after Android indexes them.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) { Text("Library information") }
        Spacer(Modifier.height(16.dp))
        Text("RSS MP3 Player • Version 1.1", style = MaterialTheme.typography.labelSmall)
    }
}

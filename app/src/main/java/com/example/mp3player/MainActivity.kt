package com.riyaz.rssmp3player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Environment
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max

private const val PREFS = "rss_mp3_player"
private const val FAVOURITES = "favourites"

private data class PlayerUpdate(val tag: String, val pageUrl: String, val apkUrl: String, val notes: String)

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
            val prefs = remember { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
            var onboardingComplete by remember { mutableStateOf(prefs.getBoolean("onboarding_complete", false)) }
            var registrationComplete by remember { mutableStateOf(prefs.getBoolean("registration_complete", false)) }
            var themeName by remember { mutableStateOf(prefs.getString("theme_name", "Onyx") ?: "Onyx") }
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val darkTheme = when (themeName) {
                "White", "Light", "Warm Light", "Cool Light" -> false
                "System Default", "Auto — White + Onyx", "Auto — Light + Dark", "Auto — Light + Black" -> systemDark
                else -> true
            }
            val colors = when (themeName) {
                "White", "Light", "Auto — White + Onyx" -> lightColorScheme()
                "Black", "OLED Black", "Auto — Light + Black" -> darkColorScheme(background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF151515), primary = Color(0xFFB7C7FF))
                "Warm Light" -> lightColorScheme(primary = Color(0xFF865B35), secondary = Color(0xFF795548))
                "Cool Light" -> lightColorScheme(primary = Color(0xFF315C8C), secondary = Color(0xFF476D91))
                "High Contrast" -> if (darkTheme) darkColorScheme(onSurface = Color.White, onBackground = Color.White) else lightColorScheme(onSurface = Color.Black, onBackground = Color.Black)
                else -> if (darkTheme) darkColorScheme() else lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    if (!onboardingComplete) {
                        OnboardingFlow(onFinish = {
                            prefs.edit().putBoolean("onboarding_complete", true).apply()
                            onboardingComplete = true
                        })
                    } else if (!registrationComplete) {
                        RegistrationScreen(onRegister = { fullName, email ->
                            prefs.edit().putBoolean("registration_complete", true)
                                .putString("profile_name", fullName).putString("profile_email", email).apply()
                            registrationComplete = true
                        })
                    } else if (!granted.value) {
                        PermissionScreen(onGrant = { requestPermissions() })
                    } else {
                        PlayerScreen(
                            player = controller.value,
                            songs = songs.value,
                            darkTheme = darkTheme,
                            themeName = themeName,
                            onThemeChange = { themeName = it; prefs.edit().putString("theme_name", it).apply() },
                            onDarkThemeChange = { enabled -> themeName = if (enabled) "Dark" else "Light"; prefs.edit().putString("theme_name", themeName).apply() },
                            recordGranted = recordGranted.value,
                            onRequestRecordPermission = { recordLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            openNowPlayingOnLaunch = intent.getBooleanExtra("com.riyaz.rssmp3player.OPEN_NOW_PLAYING", false)
                        )
                    }
                }
            }
        }
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
private fun OnboardingFlow(onFinish: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        delay(1100)
        if (page == 0) page = 1
    }
    val titles = listOf(
        "Your music, your way",
        "A player built for listening",
        "Keep your library in control",
        "Make it sound like yours"
    )
    val descriptions = listOf(
        "Welcome to RSS Mp3 Player",
        "Swipe between tracks, manage your queue, and continue where you stopped.",
        "Browse your music, save favourites, and use the home-screen playback widget.",
        "Choose a theme, explore audio controls, and set a sleep timer."
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (page == 0) {
            Spacer(Modifier.weight(1f))
            Text("♫", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text("RSS Mp3 Player", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text("Music that moves with you", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            CircularProgressIndicator()
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onFinish) { Text("Skip") }
            }
            Crossfade(targetState = page, label = "feature walkthrough") { currentPage ->
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        when (currentPage) {
                            1 -> "♫"
                            2 -> "▤"
                            else -> "◉"
                        },
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(28.dp))
                    Text(titles[currentPage], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        descriptions[currentPage],
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (dot in 1..3) {
                    Box(
                        Modifier.size(if (page == dot) 10.dp else 7.dp)
                            .background(
                                if (page == dot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(50)
                            )
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (page < 3) page++ else onFinish() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (page == 3) "Start listening" else "Continue") }
        }
    }
}

@Composable
private fun RegistrationScreen(onRegister: (String, String) -> Unit) {
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("♫", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Create your RSS Mp3 Player account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Enter your details to get started.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(value = fullName, onValueChange = { fullName = it; error = null }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = email, onValueChange = { email = it; error = null }, label = { Text("Email address") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = password, onValueChange = { password = it; error = null }, label = { Text("Password") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = confirmation, onValueChange = { confirmation = it; error = null }, label = { Text("Confirm password") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            error = when {
                fullName.isBlank() -> "Enter your full name."
                !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Enter a valid email address."
                password.length < 8 -> "Password must contain at least 8 characters."
                password != confirmation -> "Passwords do not match."
                else -> null
            }
            if (error == null) onRegister(fullName.trim(), email.trim())
        }, modifier = Modifier.fillMaxWidth()) { Text("Create account") }
        Spacer(Modifier.height(8.dp))
        Text("Password is not stored on this device.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    themeName: String,
    onThemeChange: (String) -> Unit,
    onDarkThemeChange: (Boolean) -> Unit,
    recordGranted: Boolean,
    onRequestRecordPermission: () -> Unit,
    openNowPlayingOnLaunch: Boolean = false
) {
    if (player == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        PlayerContent(player, songs, darkTheme, themeName, onThemeChange, onDarkThemeChange, recordGranted, onRequestRecordPermission, openNowPlayingOnLaunch)
    }
}

private fun fmt(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0L) / 60000, (ms.coerceAtLeast(0L) / 1000) % 60)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerContent(
    player: MediaController,
    songs: List<Song>,
    darkTheme: Boolean,
    themeName: String,
    onThemeChange: (String) -> Unit,
    onDarkThemeChange: (Boolean) -> Unit,
    recordGranted: Boolean,
    onRequestRecordPermission: () -> Unit,
    openNowPlayingOnLaunch: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val audioSessionId = remember(player) { prefs.getInt("audio_session_id", 0) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var title by remember { mutableStateOf(player.mediaMetadata.title?.toString() ?: "Choose a song to play") }
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
    var volumeBoost by remember { mutableFloatStateOf(prefs.getInt("volume_boost", 0).toFloat()) }
    var eqLevels by remember { mutableStateOf(
        listOf(0f, 0f, 0f, 0f, 0f)
    ) }
    var visualizerEnabled by remember { mutableStateOf(prefs.getBoolean("visualizer", true)) }
    var showQueue by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(openNowPlayingOnLaunch) }
    LaunchedEffect(openNowPlayingOnLaunch) {
        if (openNowPlayingOnLaunch) showNowPlaying = true
    }
    var showSleepTimer by remember { mutableStateOf(false) }
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var propertiesSong by remember { mutableStateOf<Song?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var autoUpdates by remember { mutableStateOf(prefs.getBoolean("auto_updates", true)) }
    var updateInfo by remember { mutableStateOf<PlayerUpdate?>(null) }
    var checkingUpdates by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()
    var resumePlayback by remember { mutableStateOf(prefs.getBoolean("resume_playback", true)) }
    var restoreAttempted by remember { mutableStateOf(false) }
    var showWhatsNew by remember {
        val version = try { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString() } catch (_: Exception) { "1" }
        mutableStateOf(prefs.getString("last_whats_new_version", "") != version)
    }
    val visualizerBars = remember { mutableStateListOf<Float>().apply { repeat(36) { add(0.04f) } } }

    LaunchedEffect(autoUpdates) {
        if (autoUpdates) {
            delay(900)
            checkingUpdates = true
            val found = withContext(Dispatchers.IO) { fetchLatestPlayerRelease(context) }
            checkingUpdates = false
            if (found != null) updateInfo = found
        }
    }

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
                        .setArtworkUri(s.artworkUri)
                        .build()
                ).build()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaMetadataChanged(md: MediaMetadata) {
                title = md.title?.toString() ?: "Choose a song to play"
                artist = md.artist?.toString() ?: ""
                currentId = player.currentMediaItem?.mediaId
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                title = mediaItem?.mediaMetadata?.title?.toString() ?: "Choose a song to play"
                artist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                currentId = mediaItem?.mediaId
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
            override fun onRepeatModeChanged(mode: Int) { repeat = mode }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, songs, resumePlayback) {
        if (!restoreAttempted && songs.isNotEmpty()) {
            if (player.mediaItemCount > 0) {
                restoreAttempted = true
            } else if (resumePlayback) {
                val savedIds = prefs.getString("saved_queue_ids", "").orEmpty()
                    .split(",").filter { it.isNotBlank() }
                val byId = songs.associateBy { it.id.toString() }
                val restoredSongs = savedIds.mapNotNull { byId[it] }
                val savedCurrentId = prefs.getString("saved_current_id", null)
                val currentIndex = restoredSongs.indexOfFirst { it.id.toString() == savedCurrentId }
                if (restoredSongs.isNotEmpty()) {
                    val restoredItems = restoredSongs.map { song ->
                        MediaItem.Builder().setMediaId(song.id.toString()).setUri(song.uri)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title)
                                .setArtist(song.artist).setAlbumTitle(song.album)
                                .setArtworkUri(song.artworkUri).build()).build()
                    }
                    player.setMediaItems(restoredItems, currentIndex.coerceAtLeast(0),
                        prefs.getLong("saved_position_ms", 0L).coerceAtLeast(0L))
                    player.prepare()
                    // Restore state only: do not autoplay on launch.
                }
                restoreAttempted = true
            } else {
                restoreAttempted = true
            }
        }
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            currentId = player.currentMediaItem?.mediaId
            if (resumePlayback && player.mediaItemCount > 0 && player.currentMediaItem != null) {
                prefs.edit()
                    .putString("saved_queue_ids", (0 until player.mediaItemCount)
                        .map { player.getMediaItemAt(it).mediaId }.joinToString(","))
                    .putString("saved_current_id", player.currentMediaItem?.mediaId)
                    .putLong("saved_position_ms", player.currentPosition.coerceAtLeast(0L))
                    .apply()
            }
            delay(2000)
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
    val loudnessEnhancer = remember(audioSessionId) {
        try { if (audioSessionId > 0) LoudnessEnhancer(audioSessionId).apply { enabled = true } else null }
        catch (_: Exception) { null }
    }
    LaunchedEffect(loudnessEnhancer, volumeBoost) {
        try { loudnessEnhancer?.setTargetGain((volumeBoost * 10).toInt()) } catch (_: Exception) {}
    }
    DisposableEffect(equalizer, bassBoost, loudnessEnhancer) {
        onDispose {
            try { equalizer?.release() } catch (_: Exception) {}
            try { bassBoost?.release() } catch (_: Exception) {}
            try { loudnessEnhancer?.release() } catch (_: Exception) {}
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
            val matchesFavourite = tab != 5 || favourites.contains(song.id.toString())
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
                Text("RSS Mp3 Player", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("${songs.size} songs in your library", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { tab = 4 }) { Text("Settings") }
        }

        val tabs = listOf("Songs", "Folders", "Albums", "Equalizer", "Settings", "Favourites")
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }

        when (tab) {
            3 -> EqualizerScreen(
                equalizer = equalizer,
                bassBoost = bassBoost,
                volumeBoost = volumeBoost,
                onVolumeBoostChange = { volumeBoost = it; prefs.edit().putInt("volume_boost", it.toInt()).apply() },
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
            4 -> SettingsScreen(
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
                sortMode = sortMode,
                onSortChange = {
                    sortMode = it
                    prefs.edit().putString("sort", it).apply()
                },
                onRescan = { /* MediaStore is refreshed by the app's library refresh path. */ },
                themeName = themeName, onThemeChange = onThemeChange,
                autoUpdates = autoUpdates,
                onAutoUpdatesChange = { autoUpdates = it; prefs.edit().putBoolean("auto_updates", it).apply() },
                resumePlayback = resumePlayback,
                onResumePlaybackChange = { resumePlayback = it; prefs.edit().putBoolean("resume_playback", it).apply() },
                checkingUpdates = checkingUpdates,
                onCheckUpdates = {
                    updateScope.launch {
                        checkingUpdates = true
                        val found = withContext(Dispatchers.IO) { fetchLatestPlayerRelease(context) }
                        checkingUpdates = false
                        if (found != null) updateInfo = found
                        else infoMessage = "No newer RSS Mp3 Player release was found. A published release is required for update delivery."
                    }
                },
                onSleepTimer = { showSleepTimer = true }, onQueue = { showQueue = true }
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
                                    Modifier.fillMaxWidth().combinedClickable(
                                        onClick = {
                                        val index = songs.indexOfFirst { it.id == song.id }
                                        if (index >= 0) {
                                            player.setMediaItems(mediaItems, index, 0L)
                                            player.prepare()
                                            player.play()
                                        }
                                        },
                                        onLongClick = { actionSong = song }
                                    ).padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(46.dp).background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(14.dp)
                                        ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("♫", style = MaterialTheme.typography.titleLarge,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title.ifBlank { "Unknown title" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        Text(song.artist.ifBlank { "Unknown artist" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${song.album.ifBlank { "Unknown album" }}  •  ${fmt(song.durationMs)}", maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            visualizerBars = visualizerBars,
            visualizerEnabled = visualizerEnabled,
            onVisualizerToggle = {
                visualizerEnabled = it
                prefs.edit().putBoolean("visualizer", it).apply()
            },
            repeat = repeat, onRepeat = {
                player.repeatMode = when (repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            },
            onOpenNowPlaying = { showNowPlaying = true },
            onOpenQueue = { showQueue = true }
        )
        PlayerExtras(
            player = player, songs = songs, prefs = prefs,
            showQueue = showQueue, onQueueDismiss = { showQueue = false },
            showNowPlaying = showNowPlaying, onNowPlayingDismiss = { showNowPlaying = false },
            showSleepTimer = showSleepTimer, onSleepTimerDismiss = { showSleepTimer = false },
            actionSong = actionSong, onActionSongDismiss = { actionSong = null },
            propertiesSong = propertiesSong, onPropertiesDismiss = { propertiesSong = null },
            favourites = favourites, onToggleFavourite = { id -> favourites = if (favourites.contains(id)) favourites - id else favourites + id; prefs.edit().putStringSet(FAVOURITES, favourites).apply() },
            onMessage = { infoMessage = it }
        )
        infoMessage?.let { message ->
            AlertDialog(onDismissRequest = { infoMessage = null }, title = { Text("RSS Mp3 Player") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = { infoMessage = null }) { Text("OK") } })
        }
        updateInfo?.let { update ->
            AlertDialog(
                onDismissRequest = { updateInfo = null },
                title = { Text("RSS Mp3 Player update available") },
                text = { Text("Version ${update.tag} is available. ${update.notes.take(350).ifBlank { "Download the official update package." }}\n\nAndroid will ask you to approve installation after download.") },
                confirmButton = {
                    TextButton(onClick = {
                        updateInfo = null
                        try {
                            val uri = Uri.parse(update.apkUrl)
                            require(uri.scheme == "https" && uri.host == "github.com" && uri.path.orEmpty().startsWith("/riyazamra1/RSS-Mp3-Player/releases/download/"))
                            val request = DownloadManager.Request(uri)
                                .setTitle("RSS Mp3 Player ${update.tag}")
                                .setDescription("Downloading the official RSS Mp3 Player update")
                                .setMimeType("application/vnd.android.package-archive")
                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                .setAllowedOverMetered(true)
                                .setAllowedOverRoaming(false)
                                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "RSS-Mp3-Player-${update.tag}.apk")
                            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            manager.enqueue(request)
                            infoMessage = "Update download started. When Android shows the download-complete notification, tap it to review and install the update."
                        } catch (_: Exception) {
                            infoMessage = "Could not start the update download. Open the official release page to download it manually."
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.pageUrl))) } catch (_: Exception) {}
                        }
                    }) { Text("Download update") }
                },
                dismissButton = { TextButton(onClick = { updateInfo = null }) { Text("Later") } }
            )
        }
        if (showWhatsNew) {
            val acknowledge = {
                showWhatsNew = false
                val version = try { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString() } catch (_: Exception) { "1" }
                prefs.edit().putString("last_whats_new_version", version).apply()
            }
            AlertDialog(onDismissRequest = acknowledge, title = { Text("What’s New in RSS Mp3 Player") },
                text = { Text("• Swipe between tracks\n• Queue and Play Next\n• Sleep timer / auto-close\n• Full Now Playing with local artwork\n• Home-screen playback widget\n• RSS KIT themes\n• Resume last song and position") },
                confirmButton = { TextButton(onClick = acknowledge) { Text("Got it") } })
        }
    }
}

private suspend fun fetchLatestPlayerRelease(context: Context): PlayerUpdate? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL("https://api.github.com/repos/riyazamra1/RSS-Mp3-Player/releases/latest").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RSS-Mp3-Player-Android")
        }
        if (connection.responseCode !in 200..299) return@withContext null
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        if (json.optBoolean("draft", true) || json.optBoolean("prerelease", true)) return@withContext null
        val tag = json.optString("tag_name").trim()
        val page = json.optString("html_url").trim()
        val assets = json.optJSONArray("assets") ?: return@withContext null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://github.com/riyazamra1/RSS-Mp3-Player/releases/download/")) {
                apkUrl = url
                break
            }
        }
        if (tag.isBlank() || page.isBlank() || apkUrl == null) return@withContext null
        val current = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() } catch (_: Exception) { "1.0" }
        fun parts(v: String) = v.trim().removePrefix("v").split(".").map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val latestParts = parts(tag)
        val currentParts = parts(current)
        var comparison = 0
        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val a = latestParts.getOrElse(i) { 0 }
            val b = currentParts.getOrElse(i) { 0 }
            if (a != b) { comparison = a.compareTo(b); break }
        }
        if (comparison > 0) PlayerUpdate(tag, page, apkUrl, json.optString("body")) else null
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
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
    shuffle: Boolean, onShuffle: () -> Unit,
    visualizerBars: SnapshotStateList<Float>, visualizerEnabled: Boolean,
    onVisualizerToggle: (Boolean) -> Unit,
    repeat: Int, onRepeat: () -> Unit,
    onOpenNowPlaying: () -> Unit, onOpenQueue: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenNowPlaying)) {
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "Choose a song to play" }, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                Text(artist.ifBlank { "RSS Mp3 Player" }, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = visualizerEnabled, onCheckedChange = onVisualizerToggle)
        }
        val visualizerColor = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(42.dp)) {
            val gap = 2.dp.toPx()
            val barWidth = ((size.width - gap * (visualizerBars.size - 1)) / visualizerBars.size).coerceAtLeast(1f)
            visualizerBars.forEachIndexed { index, level ->
                val h = (size.height * level.coerceIn(0.04f, 1f)).coerceAtLeast(3f)
                drawRoundRect(
                    color = visualizerColor.copy(alpha = if (visualizerEnabled) 0.95f else 0.25f),
                    topLeft = Offset(index * (barWidth + gap), (size.height - h) / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
                )
            }
        }
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
            TextButton(onClick = onOpenQueue) { Text("Queue") }
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
private fun EqualizerScreen(
    equalizer: Equalizer?,
    bassBoost: BassBoost?,
    volumeBoost: Float,
    onVolumeBoostChange: (Float) -> Unit,
    bassLevel: Float,
    onBassChange: (Float) -> Unit,
    eqLevels: List<Float>,
    onEqChange: (Int, Float) -> Unit
) {
    var enabled by remember(equalizer) { mutableStateOf(equalizer?.enabled == true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Sound & equalizer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Adjust playback gain, bass and five equalizer bands for the current playback session.",
            style = MaterialTheme.typography.bodySmall)
        if (equalizer == null || bassBoost == null) {
            Spacer(Modifier.height(20.dp))
            Text("Sound effects are not supported by this device or the audio session is not ready. Start a track and reopen this screen.")
        } else {
            Text("Volume boost (+0 to +10 dB)", fontWeight = FontWeight.SemiBold)
            Slider(value = volumeBoost, onValueChange = onVolumeBoostChange, valueRange = 0f..100f)
            Text("Boost: +${(volumeBoost / 10f).toInt()} dB. High levels may distort audio or damage hearing; increase carefully.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
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
    sortMode: String, onSortChange: (String) -> Unit, onRescan: () -> Unit,
    themeName: String, onThemeChange: (String) -> Unit,
    autoUpdates: Boolean, onAutoUpdatesChange: (Boolean) -> Unit,
    resumePlayback: Boolean, onResumePlaybackChange: (Boolean) -> Unit,
    checkingUpdates: Boolean, onCheckUpdates: () -> Unit,
    onSleepTimer: () -> Unit, onQueue: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("RSS KIT theme", fontWeight = FontWeight.SemiBold)
        val themeOptions = listOf("Material", "White", "Onyx", "Light", "Dark", "Black", "Auto — White + Onyx", "Auto — Light + Dark", "Auto — Light + Black", "System Default", "OLED Black", "Warm Light", "Cool Light", "High Contrast", "Dynamic Color")
        var themeMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { themeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Theme: $themeName") }
            DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                themeOptions.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onThemeChange(option); themeMenu = false }) }
            }
        }
        Text("Auto themes follow the system light/dark setting.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatic update preference", fontWeight = FontWeight.SemiBold)
                Text("Updates require a published release signed with the same trusted key. Android controls installation approval.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = autoUpdates, onCheckedChange = onAutoUpdatesChange)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Resume last playback", fontWeight = FontWeight.Medium)
                Text("Restore the last queue and position without autoplaying.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = resumePlayback, onCheckedChange = onResumePlaybackChange)
        }
        OutlinedButton(onClick = onCheckUpdates, enabled = !checkingUpdates, modifier = Modifier.fillMaxWidth()) {
            Text(if (checkingUpdates) "Checking for updates…" else "Check for updates now")
        }
        Text("When enabled, the player checks GitHub Releases on startup. Android requires user approval to install updates.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onSleepTimer, modifier = Modifier.fillMaxWidth()) { Text("Sleep timer / auto-close") }
        OutlinedButton(onClick = onQueue, modifier = Modifier.fillMaxWidth()) { Text("Manage play queue") }
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
        Text("RSS KIT • RSS Mp3 Player • Version 1.1", style = MaterialTheme.typography.labelSmall)
    }
}

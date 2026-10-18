package com.riyaz.rssmp3player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import org.json.JSONObject

class PlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_SLEEP_TIMER = "com.riyaz.rssmp3player.SLEEP_TIMER"
        const val ACTION_SLEEP_TRACK = "com.riyaz.rssmp3player.SLEEP_TRACK"
        const val ACTION_SLEEP_QUEUE = "com.riyaz.rssmp3player.SLEEP_QUEUE"
        const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
        const val ACTION_REFRESH_WIDGET = "com.riyaz.rssmp3player.REFRESH_WIDGET"
        private const val PREFS = "rss_mp3_player"
        private const val SAVE_QUEUE = "saved_queue"
        private const val SAVE_INDEX = "saved_index"
        private const val SAVE_POSITION = "saved_position"
    }
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private var stopAtTrackEnd = false
    private var stopAtQueueEnd = false
    private val persistRunnable = object : Runnable {
        override fun run() {
            session?.player?.let { persistPlayback(it) }
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val player = ExoPlayer.Builder(this).setAudioAttributes(AudioAttributes.DEFAULT, true).setHandleAudioBecomingNoisy(true).build()
        prefs.edit().putInt("audio_session_id", player.audioSessionId).apply()
        restorePlayback(player, prefs)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (stopAtTrackEnd && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) stopPlayback()
                persistPlayback(player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = persistPlayback(player)
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (stopAtQueueEnd && playbackState == Player.STATE_ENDED) stopPlayback()
                persistPlayback(player)
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = persistPlayback(player)
        })
        session = MediaSession.Builder(this, player).build()
        handler.postDelayed(persistRunnable, 5_000L)
        persistPlayback(player)
    }

    private fun restorePlayback(player: ExoPlayer, prefs: android.content.SharedPreferences) {
        try {
            val json = JSONArray(prefs.getString(SAVE_QUEUE, "[]") ?: "[]")
            val items = mutableListOf<MediaItem>()
            for (i in 0 until json.length()) {
                val entry = json.getJSONObject(i)
                val uri = Uri.parse(entry.getString("uri"))
                val md = MediaMetadata.Builder().setTitle(entry.optString("title", "Unknown title"))
                    .setArtist(entry.optString("artist", "Unknown artist")).setAlbumTitle(entry.optString("album", "Unknown album")).build()
                items += MediaItem.Builder().setMediaId(entry.optString("id", uri.toString())).setUri(uri).setMediaMetadata(md).build()
            }
            if (items.isNotEmpty()) {
                player.setMediaItems(items, prefs.getInt(SAVE_INDEX, 0).coerceIn(0, items.lastIndex), prefs.getLong(SAVE_POSITION, 0L).coerceAtLeast(0L))
                player.prepare()
                player.pause()
            }
        } catch (_: Exception) { /* Ignore stale/corrupt saved queue without blocking startup. */ }
    }

    private fun persistPlayback(player: Player) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queue = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            queue.put(JSONObject().put("id", item.mediaId).put("uri", item.localConfiguration?.uri?.toString().orEmpty())
                .put("title", item.mediaMetadata.title?.toString().orEmpty()).put("artist", item.mediaMetadata.artist?.toString().orEmpty())
                .put("album", item.mediaMetadata.albumTitle?.toString().orEmpty()))
        }
        prefs.edit().putString(SAVE_QUEUE, queue.toString()).putInt(SAVE_INDEX, player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong(SAVE_POSITION, player.currentPosition.coerceAtLeast(0L))
            .putString("widget_title", player.mediaMetadata.title?.toString() ?: "RSS Music")
            .putString("widget_artist", player.mediaMetadata.artist?.toString() ?: "Tap to choose music")
            .putBoolean("widget_playing", player.isPlaying).putLong("widget_position", player.currentPosition.coerceAtLeast(0L))
            .putLong("widget_duration", player.duration.coerceAtLeast(0L))
            .putString("widget_artwork_uri", player.mediaMetadata.artworkUri?.toString().orEmpty()).apply()
        sendBroadcast(Intent(this, PlaybackWidgetProvider::class.java).setAction(ACTION_REFRESH_WIDGET))
    }

    private fun cancelSleepTimer() {
        sleepRunnable?.let(handler::removeCallbacks); sleepRunnable = null
        stopAtTrackEnd = false; stopAtQueueEnd = false
    }
    private fun stopPlayback() {
        cancelSleepTimer()
        val player = session?.player ?: return
        player.pause(); player.clearMediaItems()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(SAVE_POSITION, 0L).putInt(SAVE_INDEX, 0).putString(SAVE_QUEUE, "[]").apply()
        persistPlayback(player)
        stopSelf()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SLEEP_TIMER -> {
                cancelSleepTimer()
                val minutes = intent.getIntExtra(EXTRA_SLEEP_MINUTES, 30).coerceIn(1, 240)
                sleepRunnable = Runnable { stopPlayback() }.also { handler.postDelayed(it, minutes * 60_000L) }
            }
            ACTION_SLEEP_TRACK -> { cancelSleepTimer(); stopAtTrackEnd = true }
            ACTION_SLEEP_QUEUE -> { cancelSleepTimer(); stopAtQueueEnd = true }
        }
        return super.onStartCommand(intent, flags, startId)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        handler.removeCallbacks(persistRunnable); cancelSleepTimer()
        session?.run { persistPlayback(player); player.release(); release() }
        session = null
        super.onDestroy()
    }
}

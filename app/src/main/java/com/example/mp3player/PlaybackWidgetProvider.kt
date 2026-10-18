package com.example.mp3player
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

class PlaybackWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { updateOne(context, manager, it) } }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == PlaybackService.ACTION_REFRESH_WIDGET) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlaybackWidgetProvider::class.java))
            onUpdate(context, manager, ids)
        }
    }
    companion object {
        fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("rss_mp3_player", Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.rss_music_widget)
            views.setTextViewText(R.id.widget_title, prefs.getString("widget_title", "RSS Music"))
            views.setTextViewText(R.id.widget_artist, prefs.getString("widget_artist", "Tap to choose music"))
            views.setImageViewResource(R.id.widget_play, if (prefs.getBoolean("widget_playing", false)) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            val art = prefs.getString("widget_artwork_uri", "").orEmpty()
            if (art.isNotBlank()) try { views.setImageViewUri(R.id.widget_art, Uri.parse(art)) } catch (_: Exception) {}
            views.setOnClickPendingIntent(R.id.widget_root, WidgetActionReceiver.pending(context, WidgetActionReceiver.ACTION_OPEN, id))
            views.setOnClickPendingIntent(R.id.widget_previous, WidgetActionReceiver.pending(context, WidgetActionReceiver.ACTION_PREVIOUS, id))
            views.setOnClickPendingIntent(R.id.widget_play, WidgetActionReceiver.pending(context, WidgetActionReceiver.ACTION_TOGGLE, id))
            views.setOnClickPendingIntent(R.id.widget_next, WidgetActionReceiver.pending(context, WidgetActionReceiver.ACTION_NEXT, id))
            views.setOnClickPendingIntent(R.id.widget_queue, WidgetActionReceiver.pending(context, WidgetActionReceiver.ACTION_QUEUE, id))
            manager.updateAppWidget(id, views)
        }
    }
}

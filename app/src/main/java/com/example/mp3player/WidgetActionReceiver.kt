package com.example.mp3player
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

class WidgetActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_OPEN = "com.example.mp3player.widget.OPEN"
        const val ACTION_PREVIOUS = "com.example.mp3player.widget.PREVIOUS"
        const val ACTION_TOGGLE = "com.example.mp3player.widget.TOGGLE"
        const val ACTION_NEXT = "com.example.mp3player.widget.NEXT"
        const val ACTION_QUEUE = "com.example.mp3player.widget.QUEUE"
        fun pending(context: Context, action: String, id: Int): PendingIntent {
            val i = Intent(context, WidgetActionReceiver::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            return PendingIntent.getBroadcast(context, id * 31 + action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_OPEN || intent.action == ACTION_QUEUE) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_queue", intent.action == ACTION_QUEUE))
            return
        }
        val pending = goAsync()
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                when (intent.action) {
                    ACTION_PREVIOUS -> controller.seekToPreviousMediaItem()
                    ACTION_TOGGLE -> if (controller.isPlaying) controller.pause() else controller.play()
                    ACTION_NEXT -> controller.seekToNextMediaItem()
                }
                controller.release()
            } catch (_: Exception) {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } finally { MediaController.releaseFuture(future); pending.finish() }
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlaybackWidgetProvider::class.java))
            PlaybackWidgetProvider().onUpdate(context, manager, ids)
        }, ContextCompat.getMainExecutor(context))
    }
}

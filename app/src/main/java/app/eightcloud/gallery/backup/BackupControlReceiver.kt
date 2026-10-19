package app.eightcloud.gallery.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification action target: toggles upload pause/resume. */
class BackupControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_PAUSE) {
            BackupControl.setPaused(context, !BackupControl.isPaused(context))
        }
    }

    companion object {
        const val ACTION_TOGGLE_PAUSE = "app.eightcloud.gallery.action.TOGGLE_UPLOAD_PAUSE"
    }
}

package dev.pear.remote.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.pear.remote.app.PearRemoteApp
import dev.pear.remote.app.ui.MainActivity
import dev.pear.remote.client.ConnectionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Hosts the media session: notification, lock-screen controls, media
 * buttons. media3 promotes it to a foreground service (type mediaPlayback)
 * only while the desktop is playing. It stops itself when the connection
 * ends while the app isn't visible.
 */
@OptIn(UnstableApi::class)
class RemoteMediaService : MediaSessionService() {
    private var session: MediaSession? = null
    private var player: RemotePlayer? = null
    private val jobs = mutableListOf<Job>()

    override fun onCreate() {
        super.onCreate()
        val app = application as PearRemoteApp
        val p = RemotePlayer(Looper.getMainLooper(), app.controller, app.scope, placeholderArt())
        player = p
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, p).setSessionActivity(open).build()

        jobs += app.scope.launch { app.controller.player.collect { p.update(it) } }
        jobs += app.scope.launch {
            combine(app.policy.visible, app.controller.status) { v, st -> v || st is ConnectionStatus.Connected ||
                st is ConnectionStatus.Connecting || st is ConnectionStatus.Retrying }
                .collect { keep -> if (!keep) stopSelfCleanly() }
        }
    }

    /** Renders the neutral cover placeholder (a music note on grey) as PNG. */
    private fun placeholderArt(): ByteArray? = try {
        val size = 256
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(0xFF616161.toInt())
        androidx.core.content.ContextCompat.getDrawable(this, dev.pear.remote.app.R.drawable.ic_art_placeholder)?.apply {
            setBounds(size / 4, size / 4, size * 3 / 4, size * 3 / 4)
            draw(canvas)
        }
        java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
            .also { bmp.recycle() }
    } catch (_: Exception) {
        null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as PearRemoteApp
        val playing = player?.let { it.playWhenReady && it.playbackState == androidx.media3.common.Player.STATE_READY } ?: false
        app.policy.onTaskRemoved(playing)
        if (!playing) stopSelfCleanly()
    }

    /** Stops without sending anything to the desktop (never pauses its music). */
    private fun stopSelfCleanly() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        session?.release()
        session = null
        player?.release()
        player = null
        stopSelf()
    }

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

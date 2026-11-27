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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Hosts the media session: notification, lock-screen controls, media
 * buttons. It runs as a foreground service (type mediaPlayback) while the
 * desktop has a track, playing or paused, so the connection survives the app
 * going to the background. It stops itself when the connection ends while the
 * app isn't visible.
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
        // Now-playing cover for the notification and lock screen, fetched over the paired connection.
        jobs += app.scope.launch {
            app.controller.player.map { it?.state?.track?.thumb }.distinctUntilChanged().collectLatest { ref ->
                p.setCover(null)
                if (ref == null) return@collectLatest
                val bytes = app.controller.thumbnails.load(ref) ?: return@collectLatest
                val ok = withContext(Dispatchers.Default) { dev.pear.remote.app.ui.decodeCover(bytes)?.also { it.recycle() } != null }
                if (ok) p.setCover(bytes) else app.controller.thumbnails.reportBad(ref)
            }
        }
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

    /**
     * Keeps the service in the foreground (and the notification pinned) for as
     * long as there is a track, also while paused. By default media3 only does
     * that while playing, which lets Android cache or freeze the process when
     * the app is in the background and paused, killing the socket. The
     * foreground start happens while the app is visible (the session and its
     * first notification are created then), so it stays allowed on Android 12+.
     * [ConnectionPolicy] ends the connection, and with it the service and the
     * notification, after the idle period.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val hasTrack = session.player.playbackState != androidx.media3.common.Player.STATE_IDLE
        super.onUpdateNotification(session, startInForegroundRequired || hasTrack)
    }

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

package dev.pear.remote.app

import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.pear.remote.app.data.Settings
import dev.pear.remote.app.playback.RemoteMediaService
import dev.pear.remote.client.ConnectionStatus
import dev.pear.remote.client.RemoteController
import dev.pear.remote.protocol.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Decides when a connection exists. Push-based, no polling:
 *
 * - App visible and a desktop selected: connected.
 * - App in the background and music playing on the desktop: stay connected,
 *   with the media notification (foreground service).
 * - App in the background and nothing playing: disconnect after a short
 *   grace period (so a pause from the notification can be undone), and the
 *   service stops. No background connection when nothing is playing.
 */
class ConnectionPolicy(
    private val context: Context,
    private val controller: RemoteController,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _selected = MutableStateFlow(settings.selectedDesktop)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private var graceJob: Job? = null

    val playing = controller.player.map {
        val s = it?.state?.playback?.status
        it?.state?.track != null && (s == PlaybackStatus.PLAYING || s == PlaybackStatus.BUFFERING)
    }.distinctUntilChanged()

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _visible.value = true
            }

            override fun onStop(owner: LifecycleOwner) {
                _visible.value = false
            }
        })

        // Connect when the app becomes visible or the selection changes.
        scope.launch {
            combine(_visible, _selected) { v, sel -> v to sel }.distinctUntilChanged().collect { (v, sel) ->
                if (sel == null) controller.stop() else if (v) controller.start(sel)
            }
        }
        // In the background, drop the connection once nothing is playing.
        scope.launch {
            combine(_visible, playing) { v, p -> v || p }.distinctUntilChanged().collect { needed ->
                graceJob?.cancel()
                if (!needed) {
                    graceJob = scope.launch {
                        delay(BACKGROUND_GRACE_MS)
                        controller.stop()
                    }
                }
            }
        }

        // The service hosts the media session (notification, lock screen,
        // media keys). Start it while visible and connected; it stops itself
        // once the connection is gone and the app isn't visible.
        scope.launch {
            combine(_visible, controller.status) { v, st -> v && st is ConnectionStatus.Connected }
                .distinctUntilChanged()
                .collect { start ->
                    if (start) {
                        try {
                            context.startService(Intent(context, RemoteMediaService::class.java))
                        } catch (_: IllegalStateException) {
                            // Background start not allowed; it starts next time the app is visible.
                        }
                    }
                }
        }
    }

    fun select(instanceId: String?) {
        settings.selectedDesktop = instanceId
        _selected.value = instanceId
    }

    /** "Retry" in the UI: reconnect now, also after a failure that stopped the connection. */
    fun reconnect() {
        val sel = _selected.value ?: return
        controller.stop()
        controller.start(sel)
    }

    /** Called when the user swipes the app away from recents. */
    fun onTaskRemoved(isPlaying: Boolean) {
        if (!isPlaying) controller.stop()
    }

    private companion object {
        const val BACKGROUND_GRACE_MS = 60_000L
    }
}


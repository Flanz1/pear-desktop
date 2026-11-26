package dev.pear.remote.app.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.pear.remote.client.Clock
import dev.pear.remote.client.PlayerSnapshot
import dev.pear.remote.client.RemoteController
import dev.pear.remote.protocol.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A media3 Player that mirrors the desktop's player. It plays nothing
 * locally: every control (notification, lock screen, headset buttons,
 * hardware volume keys) becomes a protocol command, and state comes from
 * the desktop's pushed events.
 *
 * The playlist is [previous placeholder, current, next placeholder] so
 * that media3 offers Previous and Next; both are sent to the desktop,
 * which owns the real queue.
 */
@OptIn(UnstableApi::class)
class RemotePlayer(
    looper: Looper,
    private val controller: RemoteController,
    private val scope: CoroutineScope,
    /** PNG shown as the cover in the notification and on the lock screen. */
    private val placeholderArt: ByteArray?,
) : SimpleBasePlayer(looper) {

    private var snapshot: PlayerSnapshot? = null

    fun update(s: PlayerSnapshot?) {
        snapshot = s
        invalidateState()
    }

    override fun getState(): State {
        val snap = snapshot
        val track = snap?.state?.track
        val b = State.Builder()
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build(),
            )
            .setMaxSeekToPreviousPositionMs(0)
        if (snap == null || track == null) {
            return b.setAvailableCommands(Player.Commands.EMPTY)
                .setPlaylist(emptyList())
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }
        val st = snap.state
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        ).build()

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            // Never the desktop's thumbnail URL: the app loads nothing from the
            // internet. A neutral placeholder stands in for the cover.
            .setArtworkData(placeholderArt, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        val durationUs = track.durationSeconds?.let { (it * 1_000_000).toLong() } ?: C.TIME_UNSET
        val current = MediaItemData.Builder(track.videoId)
            .setMediaItem(MediaItem.Builder().setMediaId(track.videoId).setMediaMetadata(metadata).build())
            .setDurationUs(durationUs)
            .setIsSeekable(track.durationSeconds != null)
            .build()
        val prev = MediaItemData.Builder("previous").build()
        val next = MediaItemData.Builder("next").build()

        val status = st.playback.status
        val anchor = snap
        return b.setAvailableCommands(commands)
            .setPlaylist(listOf(prev, current, next))
            .setCurrentMediaItemIndex(1)
            .setPlaybackState(
                when (status) {
                    PlaybackStatus.BUFFERING -> Player.STATE_BUFFERING
                    PlaybackStatus.STOPPED -> Player.STATE_ENDED
                    else -> Player.STATE_READY
                },
            )
            .setPlayWhenReady(
                status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            )
            .setContentPositionMs { (anchor.positionAt(Clock.SYSTEM.nanos()) * 1000).toLong() }
            .setDeviceVolume(st.volume.volume)
            .setIsDeviceMuted(st.volume.muted)
            .build()
    }

    private fun run(block: suspend () -> Unit): ListenableFuture<*> {
        scope.launch { block() }
        // State updates arrive as desktop events; nothing to wait for here.
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = run {
        controller.session?.let { if (playWhenReady) it.play() else it.pause() }
    }

    override fun handleStop() = run { controller.session?.pause() }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = run {
        val s = controller.session ?: return@run
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> s.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> s.previous()
            else -> s.seek(positionMs.coerceAtLeast(0) / 1000.0)
        }
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int) = run {
        controller.session?.setVolume(deviceVolume)
    }

    override fun handleIncreaseDeviceVolume(flags: Int) = run {
        val v = snapshot?.state?.volume?.volume ?: return@run
        controller.session?.setVolume(v + VOLUME_STEP)
    }

    override fun handleDecreaseDeviceVolume(flags: Int) = run {
        val v = snapshot?.state?.volume?.volume ?: return@run
        controller.session?.setVolume(v - VOLUME_STEP)
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int) = run {
        controller.session?.setMuted(muted)
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        const val VOLUME_STEP = 5
    }
}

package dev.pear.remote.app.ui

import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pear.remote.app.R
import dev.pear.remote.client.ConnectionStatus
import dev.pear.remote.client.PlayerSnapshot
import dev.pear.remote.protocol.LikeStatus
import dev.pear.remote.protocol.PlaybackStatus
import dev.pear.remote.protocol.RepeatMode
import kotlinx.coroutines.delay

/**
 * The full player. As Now Playing (opened from the mini player) it has a
 * back arrow; with a desktop that has no catalog it is the main screen, as
 * in 1.0, with a short note that updating Pear Desktop adds browsing.
 */
@Composable
fun RemoteScreen(
    vm: RemoteViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showUpdateNote: Boolean = false,
) {
    val status by vm.status.collectAsState()
    val snap by vm.player.collectAsState()

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
            Text(
                if (onBack != null) stringResource(R.string.remote_now_playing) else desktopName(status) ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { vm.open(Screen.Queue) }, enabled = snap != null) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(R.string.remote_queue))
            }
            IconButton(onClick = { vm.open(Screen.Desktops) }) {
                Icon(Icons.Filled.Computer, contentDescription = stringResource(R.string.remote_computers))
            }
        }

        StatusBanner(status, vm)
        if (showUpdateNote) {
            Text(
                stringResource(R.string.browse_update_desktop),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val s = snap
        if (s == null) return@Column
        val track = s.state.track
        if (track == null) {
            Text(stringResource(R.string.remote_nothing_playing), style = MaterialTheme.typography.bodyLarge)
        } else {
            // From Track.thumb via catalog.thumb (1.1); the placeholder otherwise.
            Cover(
                track.thumb,
                Modifier.fillMaxWidth().widthIn(max = 480.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .align(Alignment.CenterHorizontally),
            )
            Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.artist, track.album).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SeekBar(s, vm)
        }
        Controls(s, vm)
        VolumeRow(s, vm)
    }
}

@Composable
private fun desktopName(st: ConnectionStatus): String? = when (st) {
    is ConnectionStatus.Connected -> st.welcome.name
    is ConnectionStatus.Connecting -> st.desktop.name
    is ConnectionStatus.Retrying -> st.desktop.name
    is ConnectionStatus.Stopped -> st.desktop?.name
    ConnectionStatus.Idle -> null
}

/** Connection state with Retry / Pair again; nothing while connected. */
@Composable
fun StatusBanner(st: ConnectionStatus, vm: RemoteViewModel) {
    val text: String
    var action: (@Composable () -> Unit)? = null
    when (st) {
        is ConnectionStatus.Connected, ConnectionStatus.Idle -> return
        is ConnectionStatus.Connecting -> text = stringResource(R.string.status_connecting, st.desktop.name)
        is ConnectionStatus.Retrying -> {
            text = failureText(st.failure, st.desktop.name) + " " +
                stringResource(R.string.status_retrying, (st.delayMs / 1000).toInt().coerceAtLeast(1))
            action = { TextButton(onClick = vm::retry) { Text(stringResource(R.string.status_retry)) } }
        }
        is ConnectionStatus.Stopped -> {
            text = failureText(st.failure, st.desktop?.name ?: stringResource(R.string.the_computer))
            action = if (st.failure.needsPairing) {
                { Button(onClick = { vm.open(Screen.Pair) }) { Text(stringResource(R.string.status_pair_again)) } }
            } else {
                { TextButton(onClick = vm::retry) { Text(stringResource(R.string.status_retry)) } }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

/** Interpolates the position locally while playing (no network ticks, SPEC 7.2). */
@Composable
private fun SeekBar(s: PlayerSnapshot, vm: RemoteViewModel) {
    val duration = s.state.track?.durationSeconds ?: return
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(s) {
        while (s.state.playback.status == PlaybackStatus.PLAYING) {
            now = System.nanoTime()
            delay(500)
        }
        now = System.nanoTime()
    }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val pos = if (dragging) dragValue.toDouble() else s.positionAt(now)
    val label = stringResource(R.string.remote_seek)
    val state = stringResource(R.string.remote_position_state, formatTime(pos), formatTime(duration))
    Column {
        Slider(
            value = pos.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                dragging = false
                val target = dragValue.toDouble()
                vm.command { seek(target) }
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = state
            },
        )
        Row {
            Text(formatTime(pos), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
        }
    }
}

fun formatTime(seconds: Double): String {
    val t = seconds.toLong().coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Play/pause, skip, shuffle, repeat and like. Also used on the queue screen. */
@Composable
fun Controls(s: PlayerSnapshot, vm: RemoteViewModel, compact: Boolean = false) {
    val st = s.state
    val playing = st.playback.status == PlaybackStatus.PLAYING || st.playback.status == PlaybackStatus.BUFFERING
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) {
            IconToggleButton(checked = st.shuffle, onCheckedChange = { vm.command { toggleShuffle() } }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = stringResource(if (st.shuffle) R.string.remote_shuffle_on else R.string.remote_shuffle_off),
                )
            }
        }
        IconButton(onClick = { vm.command { previous() } }) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.remote_previous))
        }
        FilledIconButton(onClick = { vm.command { togglePlay() } }, modifier = Modifier.size(if (compact) 48.dp else 64.dp)) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.remote_pause else R.string.remote_play),
            )
        }
        IconButton(onClick = { vm.command { next() } }) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.remote_next))
        }
        if (!compact) {
            val nextMode = when (st.repeat) {
                RepeatMode.NONE -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.NONE
            }
            IconToggleButton(checked = st.repeat != RepeatMode.NONE, onCheckedChange = { vm.command { setRepeat(nextMode) } }) {
                Icon(
                    if (st.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = stringResource(
                        when (st.repeat) {
                            RepeatMode.NONE -> R.string.remote_repeat_none
                            RepeatMode.ALL -> R.string.remote_repeat_all
                            RepeatMode.ONE -> R.string.remote_repeat_one
                        },
                    ),
                )
            }
        }
    }
    if (!compact && st.track != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            val liked = st.like == LikeStatus.LIKE
            val disliked = st.like == LikeStatus.DISLIKE
            IconToggleButton(
                checked = disliked,
                onCheckedChange = { vm.command { setLike(if (disliked) LikeStatus.INDIFFERENT else LikeStatus.DISLIKE) } },
            ) {
                Icon(
                    if (disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = stringResource(if (disliked) R.string.remote_disliked else R.string.remote_dislike),
                )
            }
            IconToggleButton(
                checked = liked,
                onCheckedChange = { vm.command { setLike(if (liked) LikeStatus.INDIFFERENT else LikeStatus.LIKE) } },
            ) {
                Icon(
                    if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = stringResource(if (liked) R.string.remote_liked else R.string.remote_like),
                )
            }
        }
    }
}

@Composable
fun VolumeRow(s: PlayerSnapshot, vm: RemoteViewModel) {
    val vol = s.state.volume
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else vol.volume.toFloat()
    var lastSent by remember { mutableLongStateOf(0L) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
        IconButton(onClick = { vm.command { setMuted(!vol.muted) } }) {
            Icon(
                if (vol.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(if (vol.muted) R.string.remote_unmute else R.string.remote_mute),
            )
        }
        val label = stringResource(R.string.remote_volume)
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                dragValue = it
                // At most ~10 volume commands per second while dragging (desktop rate limit is 10/s).
                val now = System.nanoTime()
                if (now - lastSent > 120_000_000L) {
                    lastSent = now
                    val v = it.roundToInt()
                    vm.command { setVolume(v) }
                }
            },
            onValueChangeFinished = {
                dragging = false
                val v = dragValue.roundToInt()
                vm.command { setVolume(v) }
            },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
    }
}

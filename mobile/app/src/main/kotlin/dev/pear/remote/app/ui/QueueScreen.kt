package dev.pear.remote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pear.remote.app.R

/**
 * The desktop's queue. Fetched with queue.get while this screen is open,
 * refreshed when the desktop sends queue.changed (no polling). Playback
 * controls stay at the bottom, one tap away.
 */
@Composable
fun QueueScreen(vm: RemoteViewModel, modifier: Modifier = Modifier) {
    val queue by vm.queue.collectAsState()
    val snap by vm.player.collectAsState()
    val list = rememberLazyListState()

    val current = queue?.items?.indexOfFirst { it.isCurrent } ?: -1
    LaunchedEffect(current) { if (current > 0) list.scrollToItem((current - 1).coerceAtLeast(0)) }

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleLarge)
        }
        val q = queue
        if (q == null || q.stale) LinearProgressIndicator(Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f), state = list) {
            if (q != null && q.items.isEmpty()) {
                item { Text(stringResource(R.string.queue_empty), Modifier.padding(16.dp)) }
            }
            items(q?.items ?: emptyList(), key = { "${it.index}-${it.videoId}" }) { item ->
                ListItem(
                    modifier = Modifier.heightIn(min = 56.dp).clickable { vm.command { playQueueItem(item) } },
                    leadingContent = {
                        Cover(item.thumb, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
                    },
                    headlineContent = {
                        Text(
                            item.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (item.isCurrent) FontWeight.Bold else null,
                        )
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(item.artist, item.durationSeconds?.let(::formatTime)).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = if (item.isCurrent) {
                        { Icon(Icons.Filled.GraphicEq, contentDescription = stringResource(R.string.queue_now_playing)) }
                    } else {
                        null
                    },
                )
            }
            if (q?.truncated == true) {
                item { Text(stringResource(R.string.queue_truncated), Modifier.padding(16.dp)) }
            }
        }
        snap?.let { s ->
            HorizontalDivider()
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(8.dp)) {
                    Controls(s, vm, compact = true)
                    VolumeRow(s, vm)
                }
            }
        }
    }
}

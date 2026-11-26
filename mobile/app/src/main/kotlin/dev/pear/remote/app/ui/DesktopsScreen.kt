package dev.pear.remote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pear.remote.app.R
import dev.pear.remote.client.DesktopCredentials

@Composable
fun DesktopsScreen(vm: RemoteViewModel, modifier: Modifier = Modifier) {
    val desktops by vm.desktops.collectAsState()
    val selected by vm.selected.collectAsState()
    var forget by remember { mutableStateOf<DesktopCredentials?>(null) }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.desktops_title), style = MaterialTheme.typography.headlineSmall)
        if (desktops.isEmpty()) {
            Text(stringResource(R.string.desktops_empty), style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(desktops, key = { it.instanceId }) { d ->
                ListItem(
                    modifier = Modifier.heightIn(min = 56.dp).clickable { vm.select(d) },
                    leadingContent = { Icon(Icons.Filled.Computer, contentDescription = null) },
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(d.endpoint.toString()) },
                    trailingContent = {
                        IconButton(onClick = { forget = d }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.desktops_forget))
                        }
                    },
                    tonalElevation = if (d.instanceId == selected) 4.dp else 0.dp,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Button(onClick = { vm.open(Screen.Pair) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.desktops_pair))
            }
        }
    }

    forget?.let { d ->
        AlertDialog(
            onDismissRequest = { forget = null },
            text = { Text(stringResource(R.string.desktops_forget_confirm, d.name)) },
            confirmButton = {
                TextButton(onClick = { vm.forget(d); forget = null }) { Text(stringResource(R.string.desktops_forget)) }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

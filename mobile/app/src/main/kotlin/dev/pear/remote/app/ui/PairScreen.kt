package dev.pear.remote.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pear.remote.app.R

@Composable
fun PairScreen(vm: RemoteViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.pairScan() else vm.pairFailed(R.string.pair_camera_denied)
    }
    val startScan = {
        when {
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ->
                vm.pairFailed(R.string.pair_no_camera)
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                vm.pairScan()
            // Asked only now, when the user taps Scan.
            else -> camera.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineSmall)
        }

        when (val step = vm.pairStep) {
            PairStep.Choose -> {
                Text(stringResource(R.string.pair_intro), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = startScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pair_scan))
                }
                OutlinedButton(onClick = { vm.pairManual() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pair_manual))
                }
                Discovered(vm)
            }

            PairStep.Scanning -> {
                Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyLarge)
                QrScanner(onResult = vm::onQrScanned, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                OutlinedButton(onClick = { vm.pairManual() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pair_manual))
                }
            }

            is PairStep.Manual -> {
                Discovered(vm)
                OutlinedTextField(
                    value = step.host,
                    onValueChange = { vm.editManual(it, step.port) },
                    label = { Text(stringResource(R.string.pair_host)) },
                    placeholder = { Text(stringResource(R.string.pair_host_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = step.port,
                    onValueChange = { vm.editManual(step.host, it) },
                    label = { Text(stringResource(R.string.pair_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                step.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                Button(onClick = vm::submitManual, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pair_continue))
                }
            }

            PairStep.Probing -> Busy(stringResource(R.string.pair_checking))

            is PairStep.ConfirmFingerprint -> {
                Text(stringResource(R.string.pair_fingerprint_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.pair_fingerprint_body), style = MaterialTheme.typography.bodyLarge)
                Text(
                    step.short,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    // Read out group by group.
                    modifier = Modifier.semantics { contentDescription = step.short.replace("-", ", ").toList().joinToString(" ") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { vm.confirmFingerprint(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.pair_fingerprint_yes))
                    }
                    OutlinedButton(onClick = { vm.confirmFingerprint(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.pair_fingerprint_no))
                    }
                }
            }

            is PairStep.EnterCode -> {
                OutlinedTextField(
                    value = step.code,
                    onValueChange = vm::editCode,
                    label = { Text(stringResource(R.string.pair_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = vm::submitCode,
                    enabled = step.code.length == 6,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.pair_submit)) }
            }

            is PairStep.Waiting -> Busy(stringResource(R.string.pair_waiting))

            is PairStep.Done -> {
                Text(stringResource(R.string.pair_done, step.name), style = MaterialTheme.typography.titleLarge)
                Button(onClick = { vm.open(Screen.Remote) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pair_continue))
                }
            }

            is PairStep.Failed -> {
                val text = step.message?.let { stringResource(it) }
                    ?: step.failure?.let { failureText(it, stringResource(R.string.the_computer), step.viaQr) }
                    ?: ""
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::pairRestart, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pair_try_again))
                }
            }
        }
    }
}

/** Desktops found by mDNS: tapping one fills in its address. Its fingerprint is still checked on screen. */
@Composable
private fun Discovered(vm: RemoteViewModel) {
    val found by vm.discovered.collectAsState()
    if (found.isEmpty()) return
    Text(stringResource(R.string.pair_discovered), style = MaterialTheme.typography.titleSmall)
    found.values.sortedBy { it.name }.forEach { d ->
        AssistChip(
            onClick = { vm.pairManual(d.endpoint.host, d.endpoint.port) },
            label = { Text("${d.name} (${d.endpoint})") },
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

@Composable
private fun Busy(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

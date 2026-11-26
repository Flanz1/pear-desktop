package dev.pear.remote.app.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.pear.remote.client.ConnectionStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()
    private val browse: BrowseViewModel by viewModels()

    /** Home/Search/Library are shown (the desktop offers the catalog). */
    private val browsing get() = browse.supportKnown && browse.support.browse

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val handled = if (vm.screen == Screen.Remote && browsing) browse.back() else vm.back()
                if (!handled) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setContent {
            PearTheme {
                val snackbar = remember { SnackbarHostState() }
                val text = this@MainActivity
                LaunchedEffect(Unit) {
                    launch { vm.messages.collect { snackbar.showSnackbar(text.getString(it)) } }
                    launch { browse.messages.collect { snackbar.showSnackbar(text.getString(it)) } }
                }
                val status by vm.status.collectAsState()
                val shell = vm.screen == Screen.Remote && browsing
                // The shell has its own snackbar host above the mini player and navigation bar.
                Scaffold(snackbarHost = { if (!shell) SnackbarHost(snackbar) }) { padding ->
                    val m = Modifier.padding(padding)
                    when (vm.screen) {
                        Screen.Desktops -> DesktopsScreen(vm, m)
                        Screen.Pair -> PairScreen(vm, m)
                        Screen.Remote -> if (shell) {
                            MainShell(vm, browse, snackbar, m)
                        } else {
                            // A desktop without the catalog (1.0, or no capability): the remote as before.
                            val connected = status as? ConnectionStatus.Connected
                            RemoteScreen(vm, m, showUpdateNote = connected != null && browse.supportKnown)
                        }
                        Screen.NowPlaying -> RemoteScreen(vm, m, onBack = { vm.back() })
                        Screen.Queue -> QueueScreen(vm, m)
                    }
                }
            }
        }
    }

    /**
     * Hardware volume keys change the desktop's volume while the app is open
     * (the media session covers the lock screen and background).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_STEP
            KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_STEP
            else -> return super.onKeyDown(keyCode, event)
        }
        return vm.adjustVolume(delta) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && vm.player.value != null) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private companion object {
        const val VOLUME_STEP = 5
    }
}

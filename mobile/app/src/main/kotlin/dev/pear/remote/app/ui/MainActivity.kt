package dev.pear.remote.app.ui

import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!vm.back()) {
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
                    vm.messages.collect { snackbar.showSnackbar(text.getString(it)) }
                }
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    val m = Modifier.padding(padding)
                    when (vm.screen) {
                        Screen.Desktops -> DesktopsScreen(vm, m)
                        Screen.Pair -> PairScreen(vm, m)
                        Screen.Remote -> RemoteScreen(vm, m)
                        Screen.Queue -> QueueScreen(vm, m)
                    }
                }
            }
        }
    }
}

package dev.pear.remote.app

import android.app.Application
import android.os.Build
import android.provider.Settings as SystemSettings
import dev.pear.remote.app.data.KeystoreCredentialStore
import dev.pear.remote.app.data.NsdDiscovery
import dev.pear.remote.app.data.Settings
import dev.pear.remote.client.RemoteController
import dev.pear.remote.protocol.AppInfo
import dev.pear.remote.protocol.Fields
import dev.pear.remote.protocol.PairDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide objects. One connection per process, owned by [ConnectionPolicy]. */
class PearRemoteApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: KeystoreCredentialStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var discovery: NsdDiscovery
        private set
    lateinit var controller: RemoteController
        private set
    lateinit var policy: ConnectionPolicy
        private set

    override fun onCreate() {
        super.onCreate()
        store = KeystoreCredentialStore(this)
        settings = Settings(this)
        discovery = NsdDiscovery(this)
        controller = RemoteController(
            scope = scope,
            store = store,
            app = AppInfo("pear-remote-android", BuildConfig.VERSION_NAME),
            resolver = discovery,
        )
        policy = ConnectionPolicy(this, controller, settings, scope)
        policy.install()
    }

    /** How the desktop's Allow dialog names this phone. */
    fun pairDevice(): PairDevice {
        val model = Fields.deviceName(Build.MODEL ?: "") ?: null
        val userName = try {
            SystemSettings.Global.getString(contentResolver, SystemSettings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        val name = Fields.deviceName(userName ?: "")?.take(64)
            ?: model
            ?: "Android phone"
        return PairDevice(name, model?.take(64))
    }
}

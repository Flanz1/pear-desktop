package dev.pear.remote.client

/**
 * What the phone stores per paired desktop (SPEC 6.3 step 6). The token is
 * a secret: on Android it lives only in Keystore-backed encrypted storage.
 */
class DesktopCredentials(
    val instanceId: String,
    val deviceId: String,
    val token: String,
    val fingerprint: String,
    val lastHost: String,
    val lastPort: Int,
    val name: String,
) {
    val endpoint get() = Endpoint(lastHost, lastPort)

    fun withEndpoint(e: Endpoint) = DesktopCredentials(instanceId, deviceId, token, fingerprint, e.host, e.port, name)
    fun withName(n: String) = DesktopCredentials(instanceId, deviceId, token, fingerprint, lastHost, lastPort, n)

    override fun toString() =
        "DesktopCredentials(instanceId=$instanceId, deviceId=$deviceId, token=<redacted>, fingerprint=$fingerprint, " +
            "endpoint=$endpoint, name=$name)"
}

/** Storage for paired desktops. Implemented on Android with the Keystore. */
interface CredentialStore {
    fun all(): List<DesktopCredentials>
    fun get(instanceId: String): DesktopCredentials? = all().firstOrNull { it.instanceId == instanceId }
    fun put(credentials: DesktopCredentials)
    fun remove(instanceId: String)
}

class InMemoryCredentialStore : CredentialStore {
    private val map = LinkedHashMap<String, DesktopCredentials>()
    @Synchronized override fun all() = map.values.toList()
    @Synchronized override fun put(credentials: DesktopCredentials) { map[credentials.instanceId] = credentials }
    @Synchronized override fun remove(instanceId: String) { map.remove(instanceId) }
}

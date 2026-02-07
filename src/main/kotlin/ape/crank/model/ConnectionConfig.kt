package ape.crank.model

import java.util.UUID

enum class KnownHostsPolicy { STRICT, ACCEPT_NEW, TRUST_ALL }

data class ConnectionConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    var privateKeyPath: String = "",
    var knownHostsPolicy: KnownHostsPolicy = KnownHostsPolicy.ACCEPT_NEW,
    var keepAliveIntervalSeconds: Int = 30,
    var connectionTimeoutSeconds: Int = 30,
    var compression: Boolean = false,
    var environmentVariables: MutableMap<String, String> = mutableMapOf(),
    var proxyType: String? = null,
    var proxyHost: String? = null,
    var proxyPort: Int? = null,
    var label: String? = null,
    var color: String? = null
) {
    fun displayName(): String = name.ifEmpty { "$username@$host${if (port != 22) ":$port" else ""}" }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (host.isBlank()) errors.add("Host is required")
        if (username.isBlank()) errors.add("Username is required")
        if (privateKeyPath.isBlank()) errors.add("Private key path is required")
        if (port !in 1..65535) errors.add("Port must be 1-65535")
        if (proxyType != null) {
            if (proxyHost.isNullOrBlank()) errors.add("Proxy host is required when proxy is enabled")
            if (proxyPort == null || proxyPort!! !in 1..65535) errors.add("Proxy port must be 1-65535")
        }
        return errors
    }
}

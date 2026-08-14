package io.shulkermc.proxy

import io.shulkermc.proxy.utils.addressFromHostString
import java.net.InetSocketAddress
import java.util.Optional
import java.util.UUID
import kotlin.jvm.optionals.getOrDefault

@SuppressWarnings("detekt:MagicNumber")
object Configuration {
    val CLUSTER_NAME = getStringEnv("SHULKER_CLUSTER_NAME")

    val PROXY_TTL_SECONDS = getLongEnv("SHULKER_PROXY_TTL_SECONDS")
    val PROXY_PLAYER_DELTA_BEFORE_EXCLUSION =
        getOptionalIntEnv("SHULKER_PROXY_PLAYER_DELTA_BEFORE_EXCLUSION")
            .getOrDefault(15)
    val PROXY_PREFERRED_RECONNECT_ADDRESS: Optional<InetSocketAddress> =
        getOptionalStringEnv("SHULKER_PROXY_PREFERRED_RECONNECT_ADDRESS")
            .map { str -> addressFromHostString(str) }

    val NETWORK_ADMINS: List<UUID> =
        getOptionalStringEnv("SHULKER_NETWORK_ADMINS")
            .map {
                it.split(",")
                    .filter(String::isNotBlank)
                    .map(UUID::fromString)
            }
            .orElse(emptyList())

    /**
     * The hostnames allowed to appear as a metric label.
     *
     * AN ALLOW-LIST, NOT A HINT. The hostname a player connected with comes
     * from their handshake packet and is not validated by anything, so it is
     * exactly the kind of value that must never become a Prometheus label
     * unfiltered -- one bot varying it per connection would mint series
     * without bound. Anything not named here is reported as "other".
     *
     * Empty (the default) means every connection reports "other", which is
     * useless but safe, and is the right way round for a value nobody has
     * configured yet.
     *
     * Set it to the names players actually type, comma separated:
     *
     *     SHULKER_ANALYTICS_HOSTNAMES=play.overbound.gg,mc.overbound.gg
     */
    val ANALYTICS_HOSTNAMES: List<String> =
        getOptionalStringEnv("SHULKER_ANALYTICS_HOSTNAMES")
            .map {
                it.split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map(String::lowercase)
            }
            .orElse(emptyList())

    private fun getStringEnv(name: String): String = requireNotNull(System.getenv(name)) { "Missing $name" }

    private fun getOptionalStringEnv(name: String): Optional<String> = Optional.ofNullable(System.getenv(name))

    private fun getOptionalIntEnv(name: String): Optional<Int> =
        Optional.ofNullable(System.getenv(name))
            .map { it.toInt() }

    private fun getLongEnv(name: String): Long = getStringEnv(name).toLong()
}

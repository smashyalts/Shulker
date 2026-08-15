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

    /**
     * "allowlist" (default) or "open".
     *
     * allowlist: only ANALYTICS_HOSTNAMES and registered referral codes become
     * channels. Everything else is "other". Nothing a client sends can create
     * a channel.
     *
     * open: any hostname under ANALYTICS_DOMAIN_SUFFIXES becomes a channel on
     * sight, so a campaign subdomain starts attributing the moment DNS exists
     * and nobody has to remember to register it. Bounded by the suffix check,
     * a per-day cap on NEW channels, and the top-N export -- see
     * PlayerAnalyticsService. Without those three, "open" would be a hostname
     * label a stranger controls, which is an unbounded series count and a
     * monitoring outage waiting to be triggered.
     */
    val ANALYTICS_MODE: String =
        getOptionalStringEnv("SHULKER_ANALYTICS_MODE")
            .map(String::lowercase)
            .orElse("allowlist")

    /**
     * Suffixes a hostname must end with to become a channel in `open` mode.
     *
     * THIS IS THE BOUND THAT REPLACES THE ALLOW-LIST. Only we can create names
     * under our own domain, so restricting to it means a channel is something
     * we published rather than something a client invented. Empty means no
     * hostname qualifies, which is why `open` without it behaves exactly like
     * `allowlist` rather than like a wildcard.
     */
    val ANALYTICS_DOMAIN_SUFFIXES: List<String> =
        getOptionalStringEnv("SHULKER_ANALYTICS_DOMAIN_SUFFIXES")
            .map { splitList(it) }
            .orElse(emptyList())

    /**
     * Hostnames that must never become a channel, whatever the mode.
     *
     * A deny list on top of the bounds above, not instead of them -- for
     * retiring a campaign name, or for a subdomain that turns out to be
     * getting scanner traffic rather than players.
     */
    val ANALYTICS_BLOCKED_HOSTNAMES: List<String> =
        getOptionalStringEnv("SHULKER_ANALYTICS_BLOCKED_HOSTNAMES")
            .map { splitList(it) }
            .orElse(emptyList())

    /**
     * How many NEW channels may be created in a single UTC day, per proxy.
     *
     * The backstop for `open` mode. A campaign launch creates a handful of
     * names a week; anything creating hundreds in a day is a bot varying the
     * handshake, and this is what stops that becoming unbounded Redis keys.
     * Once the cap is hit, further unseen hostnames report as "other" -- the
     * data degrades rather than the monitoring falling over.
     */
    val ANALYTICS_NEW_CHANNELS_PER_DAY: Int =
        getOptionalIntEnv("SHULKER_ANALYTICS_NEW_CHANNELS_PER_DAY")
            .getOrDefault(DEFAULT_NEW_CHANNELS_PER_DAY)

    private const val DEFAULT_NEW_CHANNELS_PER_DAY = 200

    private fun splitList(raw: String): List<String> =
        raw.split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(String::lowercase)

    private fun getStringEnv(name: String): String = requireNotNull(System.getenv(name)) { "Missing $name" }

    private fun getOptionalStringEnv(name: String): Optional<String> = Optional.ofNullable(System.getenv(name))

    private fun getOptionalIntEnv(name: String): Optional<Int> =
        Optional.ofNullable(System.getenv(name))
            .map { it.toInt() }

    private fun getLongEnv(name: String): Long = getStringEnv(name).toLong()
}

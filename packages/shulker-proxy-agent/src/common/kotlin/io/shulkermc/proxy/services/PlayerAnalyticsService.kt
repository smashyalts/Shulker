package io.shulkermc.proxy.services

import io.shulkermc.proxy.Configuration
import io.shulkermc.proxy.ShulkerProxyAgentCommon
import io.shulkermc.proxy.platform.HookPostOrder
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * Who is playing, from where, on what, and whether they come back.
 *
 * THE PROXY IS THE ONLY PLACE THIS CAN LIVE. It is the one process that sees a
 * player arrive, sees the hostname they typed, and sees them leave; a backend
 * sees a player appear from the proxy with none of that context, and sees them
 * "leave" every time they walk through a portal into another zone.
 *
 * TWO STORES, ON PURPOSE, and the split is the whole design:
 *
 *   Prometheus  aggregates only, every label bounded (see PlayerAnalyticsMetrics)
 *   Redis       per-player facts, where a key per player is unremarkable
 *
 * Retention cannot be computed from Prometheus. "Did the players who first
 * joined a week ago come back" is a question about identity over time, and a
 * time series that has been aggregated no longer knows who anyone was. So the
 * per-player part lives in Redis -- which this agent already has a pool for --
 * and only the finished ratio is exported.
 *
 * NOTHING HERE MAY BREAK A LOGIN. Every Redis call is wrapped: analytics
 * failing is a gap in a graph, and a player being unable to join because a
 * cache is down is an outage. Failures increment a counter rather than
 * propagating, so the gap is visible instead of silent.
 */
class PlayerAnalyticsService(private val agent: ShulkerProxyAgentCommon) {
    private val metrics: PlayerAnalyticsMetrics? = createMetrics()

    /**
     * Live sessions on THIS proxy.
     *
     * Per-proxy and in-memory, which is correct because a player is connected
     * to exactly one proxy at a time. The cost is that a proxy killed
     * mid-session loses those sessions -- their playtime is not counted and no
     * logout is recorded. Accepted rather than solved: the alternative is
     * writing a session row to Redis on every join and reaping the orphans
     * later, which is a lot of machinery to make a graph slightly less wrong
     * during an event that already shows up as a proxy restart.
     */
    private val sessions = ConcurrentHashMap<UUID, Session>()

    private val hostnameAllowList: Set<String> = Configuration.ANALYTICS_HOSTNAMES.toSet()

    private var rollupTask: io.shulkermc.proxy.ProxyInterface.ScheduledTask? = null

    private data class Session(
        val startedAtMillis: Long,
        val platform: String,
    )

    init {
        this.agent.proxyInterface.addPlayerLoginHook({ player ->
            val platform = platformOf(player.uniqueId)
            val hostname = normaliseHostname(player.virtualHost)

            this.sessions[player.uniqueId] = Session(System.currentTimeMillis(), platform)
            this.metrics?.recordLogin(platform, hostname)
            this.refreshOnlineGauge()

            this.safely("login") { recordLoginInRedis(player.uniqueId, platform) }
        }, HookPostOrder.MONITOR)

        this.agent.proxyInterface.addPlayerDisconnectHook({ player ->
            val session = this.sessions.remove(player.uniqueId)
            this.refreshOnlineGauge()

            if (session != null) {
                val seconds = (System.currentTimeMillis() - session.startedAtMillis) / MILLIS_PER_SECOND
                this.metrics?.recordLogout(session.platform, seconds)
                this.safely("logout") { recordPlaytimeInRedis(player.uniqueId, seconds.toLong()) }
            }
        }, HookPostOrder.MONITOR)

        this.rollupTask =
            this.agent.proxyInterface.scheduleRepeatingTask(
                ROLLUP_INTERVAL_SECONDS,
                ROLLUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            ) { this.safely("rollup") { computeRollups() } }

        val configuredHostnames =
            if (this.hostnameAllowList.isEmpty()) {
                "none configured, every connection reported as '$HOSTNAME_OTHER'"
            } else {
                this.hostnameAllowList.joinToString(",")
            }
        this.agent.logger.info("Player analytics started (hostnames: $configuredHostnames)")
    }

    fun destroy() {
        this.rollupTask?.cancel()
        this.rollupTask = null
    }

    /**
     * Java or Bedrock, without depending on Floodgate being on the classpath.
     *
     * Floodgate mints a UUID for every Bedrock player whose top 64 bits are
     * zero -- it packs the Xbox ID into the bottom half and leaves the rest
     * empty. A real Mojang UUID is version 4 and effectively never has a zero
     * high half. That makes this a total, dependency-free test.
     *
     * The alternative everyone reaches for first is the "." username prefix,
     * which is CONFIGURABLE (Floodgate's username-prefix) and would silently
     * misclassify everyone the day someone changes it.
     */
    private fun platformOf(playerId: UUID): String =
        if (playerId.mostSignificantBits == 0L) PLATFORM_BEDROCK else PLATFORM_JAVA

    /**
     * Map a client-supplied hostname onto the allow-list, or "other".
     *
     * THIS IS THE CARDINALITY GUARD. virtualHost is a field of a packet the
     * client composes, so without this a bot opening connections with a random
     * hostname each time would mint an unbounded number of Prometheus series --
     * a monitoring outage caused by an unauthenticated stranger. Only names
     * configured on this proxy become labels.
     */
    private fun normaliseHostname(virtualHost: String?): String {
        if (virtualHost == null) return HOSTNAME_DIRECT
        return if (this.hostnameAllowList.contains(virtualHost)) virtualHost else HOSTNAME_OTHER
    }

    private fun refreshOnlineGauge() {
        val metrics = this.metrics ?: return
        // Counted from this proxy's own sessions rather than getPlayerCount(),
        // because the split by platform is the point and the platform is only
        // known here.
        var java = 0L
        var bedrock = 0L
        this.sessions.values.forEach { if (it.platform == PLATFORM_BEDROCK) bedrock++ else java++ }
        metrics.setOnline(PLATFORM_JAVA, java)
        metrics.setOnline(PLATFORM_BEDROCK, bedrock)
    }

    private fun recordLoginInRedis(
        playerId: UUID,
        platform: String,
    ) {
        val today = today()
        val id = playerId.toString()

        this.agent.cluster.jedisPool.resource.use { jedis ->
            // SETNX is what makes "new player" a fact rather than a guess: it
            // succeeds exactly once per player, ever, across every proxy. A
            // read-then-write would double-count a player whose client opens
            // two connections at once, which happens on every transfer.
            val isNew = jedis.setnx("$KEY_FIRST_SEEN$id", nowSeconds().toString()) == 1L
            if (isNew) {
                jedis.expire("$KEY_FIRST_SEEN$id", IDENTITY_TTL_SECONDS)
                jedis.sadd("$KEY_COHORT$today", id)
                jedis.expire("$KEY_COHORT$today", WINDOW_TTL_SECONDS)
                this.metrics?.recordNewPlayer(platform)
            }

            jedis.sadd("$KEY_ACTIVE$today", id)
            jedis.expire("$KEY_ACTIVE$today", WINDOW_TTL_SECONDS)
        }
    }

    private fun recordPlaytimeInRedis(
        playerId: UUID,
        seconds: Long,
    ) {
        if (seconds <= 0) return
        this.agent.cluster.jedisPool.resource.use { jedis ->
            val key = "$KEY_PLAYTIME$playerId"
            jedis.incrBy(key, seconds)
            jedis.expire(key, IDENTITY_TTL_SECONDS)
        }
    }

    /**
     * Unique-player counts and retention ratios.
     *
     * RUN BY EVERY PROXY, deliberately un-elected. All three compute the same
     * numbers from the same Redis state, so the result is identical and the
     * work is a handful of set operations every few minutes. Leader election
     * would be more machinery than the thing it coordinates.
     *
     * The consequence for queries: three proxies export three identical series
     * that differ only by pod label, so a dashboard must reduce them --
     * `max(overbound_players_unique)`, not `sum`. Summing gives triple the
     * real number, which looks plausible and is the trap to know about.
     */
    private fun computeRollups() {
        val metrics = this.metrics ?: return
        val today = today()
        val scratch = "$KEY_SCRATCH${this.agent.cluster.selfReference.name}"

        this.agent.cluster.jedisPool.resource.use { jedis ->
            metrics.setUnique(WINDOW_DAY, jedis.scard("$KEY_ACTIVE$today"))

            listOf(WINDOW_WEEK to DAYS_IN_WEEK, WINDOW_MONTH to DAYS_IN_MONTH).forEach { (window, days) ->
                val keys = (0 until days).map { "$KEY_ACTIVE${dayOffset(-it)}" }.toTypedArray()
                jedis.sunionstore(scratch, *keys)
                metrics.setUnique(window, jedis.scard(scratch))
                jedis.del(scratch)
            }

            RETENTION_COHORTS.forEach { (cohort, daysAgo) ->
                val cohortKey = "$KEY_COHORT${dayOffset(-daysAgo)}"
                val cohortSize = jedis.scard(cohortKey)

                // A cohort with nobody in it has no retention rate. Emitting 0
                // would draw a floor on the graph that reads as "everyone
                // churned" -- worst on a new deployment, where every cohort is
                // empty and the dashboard would open at 0% across the board.
                if (cohortSize == 0L) return@forEach

                jedis.sinterstore(scratch, cohortKey, "$KEY_ACTIVE$today")
                val returned = jedis.scard(scratch)
                jedis.del(scratch)

                metrics.setRetention(cohort, returned.toDouble() / cohortSize.toDouble())
            }
        }
    }

    /**
     * Run [block], turning any failure into a counter and a log line.
     *
     * Throwable rather than Exception: this runs on the login path, and an
     * Error here -- a missing class on a platform without UnifiedMetrics, most
     * likely -- would otherwise propagate into the proxy's event handling and
     * take the login with it.
     */
    private inline fun safely(
        op: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            this.metrics?.recordError(op)
            this.agent.logger.log(Level.FINE, "Player analytics '$op' failed", e)
        }
    }

    /**
     * Build and register the metric collection, or return null.
     *
     * Null is the normal answer on BungeeCord, which ships no UnifiedMetrics --
     * the class reference below then fails to resolve and is caught here. The
     * service keeps working; only the export is skipped, so Redis still
     * accumulates and the numbers are there the moment the platform can export
     * them.
     */
    private fun createMetrics(): PlayerAnalyticsMetrics? {
        return try {
            val metrics = PlayerAnalyticsMetrics()
            dev.cubxity.plugins.metrics.api.UnifiedMetricsProvider.get()
                .metricsManager
                .registerCollection(metrics)
            metrics
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            this.agent.logger.log(
                Level.INFO,
                "UnifiedMetrics not available, player analytics will record to Redis but export nothing",
            )
            null
        }
    }

    private companion object {
        const val PLATFORM_JAVA = "java"
        const val PLATFORM_BEDROCK = "bedrock"

        const val HOSTNAME_DIRECT = "direct"
        const val HOSTNAME_OTHER = "other"

        const val WINDOW_DAY = "day"
        const val WINDOW_WEEK = "week"
        const val WINDOW_MONTH = "month"

        const val DAYS_IN_WEEK = 7
        const val DAYS_IN_MONTH = 30

        val RETENTION_COHORTS = listOf("d1" to 1, "d7" to 7, "d30" to 30)

        const val KEY_FIRST_SEEN = "ob:analytics:first:"
        const val KEY_PLAYTIME = "ob:analytics:playtime:"
        const val KEY_ACTIVE = "ob:analytics:active:"
        const val KEY_COHORT = "ob:analytics:cohort:"

        // Per-proxy, because all three proxies run the same rollup and a shared
        // scratch key would have them overwriting each other's intermediate
        // result mid-computation.
        const val KEY_SCRATCH = "ob:analytics:scratch:"

        const val MILLIS_PER_SECOND = 1000.0
        const val ROLLUP_INTERVAL_SECONDS = 300L

        // Long enough to answer "did they come back" for the widest cohort with
        // room to spare, short enough that the daily sets do not accumulate
        // forever.
        const val WINDOW_TTL_SECONDS = 45L * 24 * 60 * 60

        // A player's own record outlives the windows: it is what makes a
        // returning player after a year still a returning player.
        const val IDENTITY_TTL_SECONDS = 400L * 24 * 60 * 60

        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

        // UTC, not the node's local zone. The nodes could be re-homed and the
        // day boundary would move with them, which would show up as one short
        // or long day in every retention number derived from these keys.
        fun today(): String = LocalDate.now(ZoneOffset.UTC).format(DAY_FORMAT)

        fun dayOffset(days: Int): String = LocalDate.now(ZoneOffset.UTC).plusDays(days.toLong()).format(DAY_FORMAT)
    }
}

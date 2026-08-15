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

    @Volatile private var peakJava = 0L

    @Volatile private var peakBedrock = 0L

    // The UTC day the peaks above belong to, so the rollup can tell that the
    // day has rolled over and start the next one from zero rather than
    // carrying yesterday's high water mark forever.
    @Volatile private var peakDay = ""

    /**
     * Referral codes somebody has registered, refreshed on every rollup.
     *
     * Cached rather than read per login: this is consulted on the login path,
     * and a Redis round trip per join to answer a question whose answer changes
     * a few times a week is the wrong trade. The cost is that a newly
     * registered code takes up to one rollup interval to start attributing --
     * documented rather than fixed, because the alternative is a hot read on
     * the one path that must never be slow.
     */
    @Volatile private var registeredChannels: Set<String> = emptySet()

    private val blockedHostnames: Set<String> = Configuration.ANALYTICS_BLOCKED_HOSTNAMES.toSet()
    private val domainSuffixes: List<String> = Configuration.ANALYTICS_DOMAIN_SUFFIXES

    // Open mode needs a suffix to bound it. Without one it would accept any
    // string a client sends, so it degrades to allowlist behaviour instead --
    // a misconfiguration should narrow what is recorded, never widen it.
    private val openMode: Boolean =
        Configuration.ANALYTICS_MODE == MODE_OPEN && this.domainSuffixes.isNotEmpty()

    @Volatile private var channelDay = ""

    private val seenChannels: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private data class Session(
        val startedAtMillis: Long,
        val platform: String,
        val channel: String,
        val name: String,
        // Milliseconds spent on each backend, accumulated as the player moves.
        // A player who spends an evening bouncing between hub and village has
        // one session and several entries here -- which is what makes
        // "favourite server" answerable without a row per hop.
        val serverMillis: MutableMap<String, Long> = ConcurrentHashMap(),
        @Volatile var currentServer: String? = null,
        @Volatile var currentServerSinceMillis: Long = 0L,
    ) {
        /** Bank the time spent on the server the player is leaving. */
        fun closeCurrentServer(nowMillis: Long) {
            val server = this.currentServer ?: return
            val spent = nowMillis - this.currentServerSinceMillis
            if (spent > 0) {
                this.serverMillis.merge(server, spent, Long::plus)
            }
        }
    }

    init {
        this.agent.proxyInterface.addPlayerLoginHook({ player ->
            val platform = platformOf(player.uniqueId)
            val hostname = normaliseHostname(player.virtualHost)

            // The Redis write comes first because it is what decides whether
            // this player is new, and which channel owns them. Both are needed
            // to label the metrics below, so there is nothing to be gained by
            // reordering -- and a failure here degrades to "returning player on
            // the hostname they used", which is the safe reading.
            var channel = hostname
            var returning = true
            this.safely("login") {
                val attribution = recordLoginInRedis(player.uniqueId, platform, hostname)
                channel = attribution.channel
                returning = !attribution.isNew
            }

            this.sessions[player.uniqueId] =
                Session(System.currentTimeMillis(), platform, channel, player.name)
            this.metrics?.recordLogin(platform, hostname, returning)
            this.refreshOnlineGauge()
        }, HookPostOrder.MONITOR)

        // Where the per-backend breakdown comes from. Fires after the player is
        // actually connected, so the previous server's time is banked at the
        // moment they land rather than when they asked to move -- a failed
        // connect therefore does not silently credit the destination.
        this.agent.proxyInterface.addServerPostConnectHook({ player, serverName ->
            val session = this.sessions[player.uniqueId]
            if (session != null) {
                val now = System.currentTimeMillis()
                session.closeCurrentServer(now)
                session.currentServer = serverName
                session.currentServerSinceMillis = now
            }
        }, HookPostOrder.MONITOR)

        this.agent.proxyInterface.addPlayerDisconnectHook({ player ->
            val session = this.sessions.remove(player.uniqueId)
            this.refreshOnlineGauge()

            if (session != null) {
                val endedAt = System.currentTimeMillis()
                session.closeCurrentServer(endedAt)
                val seconds = (endedAt - session.startedAtMillis) / MILLIS_PER_SECOND
                this.metrics?.recordLogout(session.platform, seconds)
                if (seconds < BOUNCE_THRESHOLD_SECONDS) {
                    this.metrics?.recordBounce(session.platform, session.channel)
                }
                this.safely("logout") { recordPlaytimeInRedis(player.uniqueId, seconds.toLong()) }
                this.safely("session-record") {
                    publishSession(player.uniqueId, session, endedAt, seconds.toLong())
                }
            }
        }, HookPostOrder.MONITOR)

        this.rollupTask =
            this.agent.proxyInterface.scheduleRepeatingTask(
                ROLLUP_INTERVAL_SECONDS,
                ROLLUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            ) { this.safely("rollup") { computeRollups() } }

        val mode =
            if (this.openMode) {
                "open under ${this.domainSuffixes.joinToString(",")}, " +
                    "max ${Configuration.ANALYTICS_NEW_CHANNELS_PER_DAY} new channels/day"
            } else if (this.hostnameAllowList.isEmpty()) {
                "allowlist, none configured -- every connection reported as '$HOSTNAME_OTHER'"
            } else {
                "allowlist: ${this.hostnameAllowList.joinToString(",")}"
            }
        this.agent.logger.info("Player analytics started ($mode)")
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

        // The deny list wins over everything, including an explicit allow-list
        // entry -- so retiring a name is one edit rather than a hunt for
        // wherever it was permitted.
        if (this.blockedHostnames.contains(virtualHost)) return HOSTNAME_OTHER

        if (this.hostnameAllowList.contains(virtualHost)) return virtualHost

        // A referral subdomain counts once someone has REGISTERED it.
        // Registration is a deliberate act that writes a Redis key, so the set
        // of acceptable channels grows by decision rather than by whatever a
        // stranger puts in a handshake.
        if (this.registeredChannels.contains(virtualHost)) return virtualHost

        if (this.openMode && this.hasKnownSuffix(virtualHost)) {
            return this.admitOpenChannel(virtualHost)
        }

        return HOSTNAME_OTHER
    }

    private fun hasKnownSuffix(host: String): Boolean = this.domainSuffixes.any { host.endsWith(it) }

    /**
     * Accept a hostname as a channel in open mode, up to the daily cap.
     *
     * THE CAP IS THE BACKSTOP, and it is deliberately in memory rather than in
     * Redis. This runs on the login path, where a round trip to answer "have I
     * seen this name today" would be paid by every joining player; the cost of
     * keeping it local is that the limit is per-proxy and therefore up to three
     * times the configured number across the fleet. That is a bound, which is
     * the property that matters -- the exact ceiling is not.
     *
     * Once the cap is reached the hostname reports as "other" rather than being
     * rejected: a player still joins and is still counted, only their channel
     * is coarser. Degrading the data beats degrading the service.
     */
    private fun admitOpenChannel(host: String): String {
        val today = today()
        if (this.channelDay != today) {
            this.channelDay = today
            this.seenChannels.clear()
        }

        if (this.seenChannels.contains(host)) return host
        if (this.seenChannels.size >= Configuration.ANALYTICS_NEW_CHANNELS_PER_DAY) return HOSTNAME_OTHER

        this.seenChannels.add(host)
        return host
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

        // Peak is tracked here rather than sampled by Prometheus, because a
        // scrape every 30s misses the spike between scrapes -- and a peak that
        // a listing site ranks you on is exactly the value that happens
        // briefly. Reset by the rollup when the UTC day rolls over.
        this.peakJava = maxOf(this.peakJava, java)
        this.peakBedrock = maxOf(this.peakBedrock, bedrock)
        metrics.setPeakConcurrent(PLATFORM_JAVA, this.peakJava)
        metrics.setPeakConcurrent(PLATFORM_BEDROCK, this.peakBedrock)
    }

    /**
     * Hand a finished session to whoever is durably storing them.
     *
     * A REDIS STREAM, NOT A DATABASE WRITE FROM HERE. The durable home for
     * session history is MongoDB, and this deliberately does not talk to it:
     *
     *   - it keeps a Mongo driver out of a jar that runs inside every proxy,
     *     where a dependency is a class-loader risk and 2MB on every Pod start;
     *   - XADD is fire-and-forget against a connection this agent already
     *     holds, so a disconnect never waits on a second database;
     *   - if the consumer is down, entries queue instead of being lost. A
     *     direct write would drop the session with the panel.
     *
     * MAXLEN is approximate (~) on purpose: exact trimming makes XADD O(n) and
     * the bound only needs to be a bound. At a few thousand sessions a day the
     * cap is days of headroom for a consumer that is only ever briefly absent.
     *
     * The consumer is the admin panel, which owns the Mongo credentials and is
     * the thing that reads this data back out anyway.
     */
    private fun publishSession(
        playerId: UUID,
        session: Session,
        endedAtMillis: Long,
        durationSeconds: Long,
    ) {
        // Serialised by hand rather than with a JSON library: the agent has no
        // serialiser on its classpath, the shape is fixed, and the only field
        // that can contain anything surprising is the player name -- which
        // Mojang restricts to [A-Za-z0-9_], and Floodgate prefixes with a
        // configured character. Escaped anyway; a Bedrock prefix is
        // configurable and this should not depend on what it is set to.
        val servers =
            session.serverMillis.entries.joinToString(",") { (name, millis) ->
                "\"${escapeJson(name)}\":${millis / 1000}"
            }

        this.agent.cluster.jedisPool.resource.use { jedis ->
            jedis.xadd(
                KEY_SESSION_STREAM,
                redis.clients.jedis.params.XAddParams.xAddParams()
                    .maxLen(SESSION_STREAM_MAXLEN)
                    .approximateTrimming(),
                mapOf(
                    "player" to playerId.toString(),
                    "name" to session.name,
                    "platform" to session.platform,
                    "channel" to session.channel,
                    "proxy" to this.agent.cluster.selfReference.name,
                    "startedAt" to session.startedAtMillis.toString(),
                    "endedAt" to endedAtMillis.toString(),
                    "seconds" to durationSeconds.toString(),
                    "servers" to "{$servers}",
                ),
            )
        }
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private data class Attribution(val channel: String, val isNew: Boolean)

    /**
     * Record the login and return who acquired this player.
     *
     * FIRST-TOUCH ATTRIBUTION, and it is deliberate. The channel is written
     * exactly once -- on the player's very first login, ever -- and never
     * updated. So a player recruited by a YouTube video stays credited to that
     * video even when they later type the bare domain from memory, which is
     * what everyone does by their third session.
     *
     * Last-touch would credit whichever hostname they most recently used, and
     * would therefore report that the main domain acquires everybody and that
     * advertising acquires nobody -- a conclusion that is both wrong and very
     * easy to believe, because it is what the numbers plainly say.
     */
    private fun recordLoginInRedis(
        playerId: UUID,
        platform: String,
        hostname: String,
    ): Attribution {
        val today = today()
        val id = playerId.toString()

        this.agent.cluster.jedisPool.resource.use { jedis ->
            // SETNX is what makes "new player" a fact rather than a guess: it
            // succeeds exactly once per player, ever, across every proxy. A
            // read-then-write would double-count a player whose client opens
            // two connections at once, which happens on every transfer.
            val isNew = jedis.setnx("$KEY_FIRST_SEEN$id", nowSeconds().toString()) == 1L

            val channel: String
            if (isNew) {
                channel = hostname
                jedis.expire("$KEY_FIRST_SEEN$id", IDENTITY_TTL_SECONDS)
                jedis.setex("$KEY_CHANNEL$id", IDENTITY_TTL_SECONDS, hostname)

                jedis.sadd("$KEY_COHORT$today", id)
                jedis.expire("$KEY_COHORT$today", WINDOW_TTL_SECONDS)
                jedis.sadd("$KEY_COHORT$today:$hostname", id)
                jedis.expire("$KEY_COHORT$today:$hostname", WINDOW_TTL_SECONDS)

                this.metrics?.recordNewPlayer(platform, hostname)
            } else {
                // A player whose channel key has aged out is not "unknown
                // channel" -- they are simply older than the retention window,
                // and lumping them into a named channel would inflate it.
                channel = jedis.get("$KEY_CHANNEL$id") ?: CHANNEL_UNATTRIBUTED
            }

            jedis.sadd("$KEY_ACTIVE$today", id)
            jedis.expire("$KEY_ACTIVE$today", WINDOW_TTL_SECONDS)
            jedis.sadd("$KEY_ACTIVE$today:$channel", id)
            jedis.expire("$KEY_ACTIVE$today:$channel", WINDOW_TTL_SECONDS)

            return Attribution(channel, isNew)
        }
    }

    private fun recordPlaytimeInRedis(
        playerId: UUID,
        seconds: Long,
    ) {
        if (seconds <= 0) return
        this.agent.cluster.jedisPool.resource.use { jedis ->
            val key = "$KEY_PLAYTIME$playerId"
            val total = jedis.incrBy(key, seconds)
            jedis.expire(key, IDENTITY_TTL_SECONDS)

            // THE QUALIFICATION GATE, and it exists because money is attached.
            //
            // A referral counts only once the referred player has actually
            // played. Without a gate, a referrer with a handful of alt accounts
            // can register a code, join once per alt and collect -- which is
            // how these programmes get drained in their first week, every time.
            //
            // Crossing the threshold is detected on the increment that passes
            // it (total >= gate > total - seconds), so the SADD happens exactly
            // once per player rather than on every disconnect thereafter.
            if (total >= QUALIFYING_PLAYTIME_SECONDS && total - seconds < QUALIFYING_PLAYTIME_SECONDS) {
                val channel = jedis.get("$KEY_CHANNEL$playerId") ?: return@use
                jedis.sadd("$KEY_QUALIFIED$channel", playerId.toString())
            }
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

        if (this.peakDay != today) {
            this.peakDay = today
            this.peakJava = 0
            this.peakBedrock = 0
        }

        // Every configured channel, plus the two synthetic ones every player
        // falls into, plus CHANNEL_ALL for the network-wide figure. Bounded by
        // configuration, which is what keeps the series count bounded too.
        val synthetic = listOf(CHANNEL_ALL, HOSTNAME_DIRECT, HOSTNAME_OTHER, CHANNEL_UNATTRIBUTED)

        this.agent.cluster.jedisPool.resource.use { jedis ->
            // Refresh the registry first, so a code registered since the last
            // rollup starts attributing on the next login rather than the one
            // after that.
            this.registeredChannels = jedis.smembers(KEY_REGISTERED_CODES) ?: emptySet()

            // THE CARDINALITY BOUND, now that channels are user-created.
            //
            // The registry is deliberately unbounded -- referrers can be added
            // forever, and Redis does not care. Prometheus does: one series per
            // channel per window per cohort, kept for as long as the data is
            // retained. So only the busiest TOP_CHANNELS are exported by name
            // and the rest are left to the `other` bucket they already fall
            // into for reporting purposes.
            //
            // The full per-referrer breakdown is not lost -- it stays in Redis,
            // which is where the payout report reads it from. Prometheus is for
            // the shape of the business, not the ledger.
            val ranked =
                (this.hostnameAllowList + this.registeredChannels + this.seenChannels)
                    .distinct()
                    .map { it to jedis.scard("$KEY_ACTIVE$today:$it") }
                    .sortedByDescending { it.second }
                    .take(TOP_CHANNELS)
                    .map { it.first }

            val channels = synthetic + ranked
            var dau = 0L
            var mau = 0L

            channels.forEach { channel ->
                val suffix = if (channel == CHANNEL_ALL) "" else ":$channel"

                val daily = jedis.scard("$KEY_ACTIVE$today$suffix")
                metrics.setUnique(WINDOW_DAY, channel, daily)

                listOf(WINDOW_WEEK to DAYS_IN_WEEK, WINDOW_MONTH to DAYS_IN_MONTH).forEach { (window, days) ->
                    val keys = (0 until days).map { "$KEY_ACTIVE${dayOffset(-it)}$suffix" }.toTypedArray()
                    jedis.sunionstore(scratch, *keys)
                    val count = jedis.scard(scratch)
                    jedis.del(scratch)
                    metrics.setUnique(window, channel, count)

                    if (channel == CHANNEL_ALL && window == WINDOW_MONTH) mau = count
                }

                if (channel == CHANNEL_ALL) dau = daily

                RETENTION_COHORTS.forEach { (cohort, daysAgo) ->
                    val cohortKey = "$KEY_COHORT${dayOffset(-daysAgo)}$suffix"
                    val cohortSize = jedis.scard(cohortKey)

                    // A cohort with nobody in it has no retention rate.
                    // Emitting 0 would draw a floor on the graph that reads as
                    // "everyone churned" -- worst on a new deployment, where
                    // every cohort is empty and every panel would open at 0%.
                    if (cohortSize == 0L) return@forEach

                    jedis.sinterstore(scratch, cohortKey, "$KEY_ACTIVE$today")
                    val returned = jedis.scard(scratch)
                    jedis.del(scratch)

                    metrics.setRetention(cohort, channel, returned.toDouble() / cohortSize.toDouble())
                }
            }

            // Guarded, not because a division by zero throws here -- it would
            // produce NaN, which Prometheus stores and every subsequent
            // aggregation then poisons.
            if (mau > 0L) metrics.setStickiness(dau.toDouble() / mau.toDouble())
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

        // The channel that ACQUIRED the player, written once and never again.
        const val KEY_CHANNEL = "ob:analytics:channel:"

        const val MODE_OPEN = "open"

        // Finished sessions, drained into MongoDB by the admin panel. A stream
        // rather than a list so the consumer can track its own position and
        // resume after a restart without re-reading everything.
        const val KEY_SESSION_STREAM = "ob:analytics:sessions"

        // Roughly a week of headroom at a few thousand sessions a day. The
        // consumer is expected to be seconds behind; this is the bound for when
        // it is not.
        const val SESSION_STREAM_MAXLEN = 100_000L

        // Referral codes somebody has registered. A hostname absent from this
        // set (and from the static allow-list) is reported as "other", which is
        // what stops a stranger's handshake creating a channel.
        const val KEY_REGISTERED_CODES = "ob:analytics:refcodes"

        // Referred players who have played long enough to count. This is the
        // set a payout is computed from -- never the raw join count.
        const val KEY_QUALIFIED = "ob:analytics:qualified:"

        // An hour of actual play. Long enough that farming it with alts costs
        // more than the commission is worth, short enough that a genuine player
        // clears it on their first evening.
        const val QUALIFYING_PLAYTIME_SECONDS = 3600L

        // How many named channels reach Prometheus. The registry itself is
        // unbounded; this is the bound on the metric labels. The full
        // per-referrer breakdown lives in Redis for the payout report.
        const val TOP_CHANNELS = 20

        // Every player, regardless of channel -- the network-wide figure.
        const val CHANNEL_ALL = "all"

        // Seen before, but their channel record has aged out. Deliberately not
        // folded into a real channel, which would inflate it.
        const val CHANNEL_UNATTRIBUTED = "unattributed"

        // Under a minute is not a short visit, it is a failed one: wrong client
        // version, server full, or kicked. Counted apart from session length so
        // a broken funnel does not read as an unengaged audience.
        const val BOUNCE_THRESHOLD_SECONDS = 60.0

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

package io.shulkermc.proxy.services

import dev.cubxity.plugins.metrics.api.metric.collector.Collector
import dev.cubxity.plugins.metrics.api.metric.collector.CollectorCollection
import dev.cubxity.plugins.metrics.api.metric.data.Bucket
import dev.cubxity.plugins.metrics.api.metric.data.CounterMetric
import dev.cubxity.plugins.metrics.api.metric.data.GaugeMetric
import dev.cubxity.plugins.metrics.api.metric.data.HistogramMetric
import dev.cubxity.plugins.metrics.api.metric.data.Labels
import dev.cubxity.plugins.metrics.api.metric.data.Metric
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder

/**
 * Everything this agent exports about players, as one UnifiedMetrics collection.
 *
 * SEPARATE CLASS SO THE UNIFIEDMETRICS TYPES LOAD LAZILY. BungeeCord builds do
 * not ship UnifiedMetrics, so touching these classes there would throw
 * NoClassDefFoundError. PlayerAnalyticsService only ever names this type inside
 * a catch(Throwable), and keeping it out of that class's own fields is what
 * makes the deferral real -- a field would be resolved when the owner loads.
 *
 * EVERY LABEL HERE IS BOUNDED, and that is the design constraint the whole file
 * is built around. Prometheus keeps one time series per distinct label
 * combination forever; a label whose values a player controls is a memory leak
 * in the monitoring stack, not a feature. So:
 *
 *   platform  java | bedrock                     -- 2 values
 *   hostname  an allow-list, everything else "other"
 *   channel   the allow-list plus direct | other | unattributed | all
 *   window    day | week | month                 -- 3 values
 *   cohort    d1 | d7 | d30                      -- 3 values
 *   kind      new | returning                    -- 2 values
 *
 * `hostname` is where a player connected THIS time; `channel` is who acquired
 * them, fixed at their first ever login. They differ for every returning
 * player, and conflating them is what makes advertising look worthless -- see
 * setRetention.
 *
 * NOTHING IS EVER LABELLED BY PLAYER. Per-player facts live in Redis, where a
 * million keys is a normal Tuesday. The rule of thumb: if a label's value comes
 * from a packet, it is normalised against a fixed set before it gets here.
 */
class PlayerAnalyticsMetrics : CollectorCollection {
    // Collected off the main thread. Nothing in here touches the proxy, so
    // there is no reason to make the server wait for a scrape.
    override val isAsync: Boolean get() = true

    private val logins = LabelledCounter()
    private val logouts = LabelledCounter()
    private val newPlayers = LabelledCounter()
    private val playtimeSeconds = LabelledCounter()
    private val errors = LabelledCounter()

    // A join that ended almost immediately. Counted separately because it is
    // acquisition LEAKAGE, not a short session: a player who lasts ten seconds
    // usually could not get in -- wrong client version, server full, kicked by
    // a plugin -- and every one of those is an advertising click that was paid
    // for and bounced. A rising bounce rate on one channel is a broken funnel,
    // not a bad audience.
    private val bounces = LabelledCounter()

    private val online = LabelledGauge()
    private val uniques = LabelledGauge()
    private val retention = LabelledGauge()
    private val stickiness = LabelledGauge()
    private val peakConcurrent = LabelledGauge()

    private val sessionSeconds = ConcurrentHashMap<String, SessionHistogram>()

    fun recordLogin(
        platform: String,
        hostname: String,
        returning: Boolean,
    ) {
        this.logins.inc(
            mapOf(
                "platform" to platform,
                "hostname" to hostname,
                // New vs returning on the SAME counter rather than two, so a
                // dashboard can show total logins and the split without adding
                // series that have to be kept in step.
                "kind" to if (returning) "returning" else "new",
            ),
        )
    }

    fun recordNewPlayer(
        platform: String,
        channel: String,
    ) {
        this.newPlayers.inc(mapOf("platform" to platform, "channel" to channel))
    }

    fun recordBounce(
        platform: String,
        channel: String,
    ) {
        this.bounces.inc(mapOf("platform" to platform, "channel" to channel))
    }

    fun recordLogout(
        platform: String,
        sessionSeconds: Double,
    ) {
        val labels = mapOf("platform" to platform)
        this.logouts.inc(labels)
        this.playtimeSeconds.add(labels, sessionSeconds)
        this.sessionSeconds.computeIfAbsent(platform) { SessionHistogram() }.observe(sessionSeconds)
    }

    fun recordError(op: String) {
        this.errors.inc(mapOf("op" to op))
    }

    fun setOnline(
        platform: String,
        count: Long,
    ) {
        this.online.set(mapOf("platform" to platform), count.toDouble())
    }

    fun setUnique(
        window: String,
        channel: String,
        count: Long,
    ) {
        this.uniques.set(mapOf("window" to window, "channel" to channel), count.toDouble())
    }

    /**
     * Retention, sliced by the channel the player was ACQUIRED through.
     *
     * THIS IS THE METRIC THAT ANSWERS "WHICH ADVERTISING WORKS". A global
     * retention rate cannot: it mixes everyone together, so a channel that
     * delivers a thousand players who all leave in a minute and a channel that
     * delivers fifty who stay for months are added up into one number that
     * describes neither. Spend decisions need the ratio per channel.
     *
     * The channel is FIRST-TOUCH and immutable -- see PlayerAnalyticsService.
     */
    fun setRetention(
        cohort: String,
        channel: String,
        ratio: Double,
    ) {
        this.retention.set(mapOf("cohort" to cohort, "channel" to channel), ratio)
    }

    /**
     * DAU/MAU, the standard stickiness ratio.
     *
     * "How much of your monthly audience shows up on a given day". 1.0 would
     * mean everyone plays daily; the number is a habit measure, and it moves
     * for reasons that raw player counts hide -- a server can grow its monthly
     * total while becoming less sticky, which is a churn problem wearing a
     * growth costume.
     */
    fun setStickiness(ratio: Double) {
        this.stickiness.set(emptyMap(), ratio)
    }

    /**
     * The day's highest simultaneous player count on this proxy.
     *
     * Peak concurrent is the number a server is actually judged by -- it is
     * what listing sites rank on and what capacity is planned against -- and an
     * average hides it completely.
     */
    fun setPeakConcurrent(
        platform: String,
        count: Long,
    ) {
        this.peakConcurrent.set(mapOf("platform" to platform), count.toDouble())
    }

    override val collectors: List<Collector>
        get() = listOf(SnapshotCollector())

    private inner class SnapshotCollector : Collector {
        override fun collect(): List<Metric> {
            val out = mutableListOf<Metric>()

            logins.emitInto(out, "overbound_player_logins_total")
            logouts.emitInto(out, "overbound_player_logouts_total")
            newPlayers.emitInto(out, "overbound_player_new_total")
            bounces.emitInto(out, "overbound_player_bounces_total")
            playtimeSeconds.emitInto(out, "overbound_player_playtime_seconds_total")
            errors.emitInto(out, "overbound_player_analytics_errors_total")

            online.emitInto(out, "overbound_players_online")
            uniques.emitInto(out, "overbound_players_unique")
            retention.emitInto(out, "overbound_player_retention_ratio")
            stickiness.emitInto(out, "overbound_player_stickiness_ratio")
            peakConcurrent.emitInto(out, "overbound_players_peak_concurrent")

            sessionSeconds.forEach { (platform, histogram) ->
                out += histogram.snapshot("overbound_player_session_seconds", mapOf("platform" to platform))
            }

            return out
        }
    }

    /**
     * A counter keyed by its label set.
     *
     * DoubleAdder rather than a lock: logins and logouts arrive on the proxy's
     * netty threads, several at once during a rollout when everyone reconnects
     * at the same moment, and a contended lock there is felt by players.
     */
    private class LabelledCounter {
        private val values = ConcurrentHashMap<Labels, DoubleAdder>()

        fun inc(labels: Labels) = this.add(labels, 1.0)

        fun add(
            labels: Labels,
            delta: Double,
        ) {
            this.values.computeIfAbsent(labels) { DoubleAdder() }.add(delta)
        }

        fun emitInto(
            out: MutableList<Metric>,
            name: String,
        ) {
            this.values.forEach { (labels, adder) ->
                out += CounterMetric(name, labels, adder.sum())
            }
        }
    }

    /**
     * A gauge keyed by its label set.
     *
     * Last-write-wins, unlike the counter next door: these are computed whole
     * on every rollup rather than accumulated, so a plain volatile-backed map
     * is the right shape. A label set that stops being written keeps its final
     * value, which is what makes a channel that saw no players today still
     * report its retention rather than vanishing from the graph.
     */
    private class LabelledGauge {
        private val values = ConcurrentHashMap<Labels, Double>()

        fun set(
            labels: Labels,
            value: Double,
        ) {
            this.values[labels] = value
        }

        fun emitInto(
            out: MutableList<Metric>,
            name: String,
        ) {
            this.values.forEach { (labels, value) ->
                out += GaugeMetric(name, labels, value)
            }
        }
    }

    /**
     * Session lengths, bucketed.
     *
     * The buckets are chosen for the questions actually asked of them: did they
     * bounce (under a minute -- a failed join, a wrong version, a full server),
     * did they look around (a few minutes), or did they play (an hour and up).
     * Evenly spaced buckets would put almost every sample in one of them and
     * answer none of those.
     */
    private class SessionHistogram {
        private val counts = LongArray(BOUNDS.size + 1)
        private var sum = 0.0
        private var count = 0L

        @Synchronized
        fun observe(seconds: Double) {
            this.sum += seconds
            this.count++
            var i = 0
            while (i < BOUNDS.size && seconds > BOUNDS[i]) i++
            this.counts[i]++
        }

        @Synchronized
        fun snapshot(
            name: String,
            labels: Labels,
        ): HistogramMetric {
            // Prometheus histogram buckets are CUMULATIVE -- each is "how many
            // samples were at most this". The array above counts each band on
            // its own, so it is summed forward here rather than at observe
            // time, where every sample would pay for it.
            var running = 0.0
            val buckets =
                Array(BOUNDS.size + 1) { i ->
                    running += this.counts[i]
                    val bound = if (i < BOUNDS.size) BOUNDS[i] else Double.POSITIVE_INFINITY
                    Bucket(bound, running)
                }
            return HistogramMetric(name, labels, this.count, this.sum, buckets)
        }

        companion object {
            private val BOUNDS =
                doubleArrayOf(
                    10.0, 30.0, 60.0, 300.0, 600.0,
                    1800.0, 3600.0, 7200.0, 14400.0, 28800.0,
                )
        }
    }
}

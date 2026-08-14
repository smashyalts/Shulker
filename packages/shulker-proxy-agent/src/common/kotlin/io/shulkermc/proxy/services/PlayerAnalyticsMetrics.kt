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
import java.util.concurrent.atomic.AtomicLong
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
 *   window    day | week | month                 -- 3 values
 *   cohort    d1 | d7 | d30                      -- 3 values
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

    private val online = ConcurrentHashMap<String, AtomicLong>()
    private val uniques = ConcurrentHashMap<String, AtomicLong>()
    private val retention = ConcurrentHashMap<String, Double>()

    private val sessionSeconds = ConcurrentHashMap<String, SessionHistogram>()

    fun recordLogin(
        platform: String,
        hostname: String,
    ) {
        this.logins.inc(mapOf("platform" to platform, "hostname" to hostname))
    }

    fun recordNewPlayer(platform: String) {
        this.newPlayers.inc(mapOf("platform" to platform))
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
        this.online.computeIfAbsent(platform) { AtomicLong() }.set(count)
    }

    fun setUnique(
        window: String,
        count: Long,
    ) {
        this.uniques.computeIfAbsent(window) { AtomicLong() }.set(count)
    }

    fun setRetention(
        cohort: String,
        ratio: Double,
    ) {
        this.retention[cohort] = ratio
    }

    override val collectors: List<Collector>
        get() = listOf(SnapshotCollector())

    private inner class SnapshotCollector : Collector {
        override fun collect(): List<Metric> {
            val out = mutableListOf<Metric>()

            logins.emitInto(out, "overbound_player_logins_total")
            logouts.emitInto(out, "overbound_player_logouts_total")
            newPlayers.emitInto(out, "overbound_player_new_total")
            playtimeSeconds.emitInto(out, "overbound_player_playtime_seconds_total")
            errors.emitInto(out, "overbound_player_analytics_errors_total")

            online.forEach { (platform, value) ->
                out += GaugeMetric("overbound_players_online", mapOf("platform" to platform), value.get())
            }
            uniques.forEach { (window, value) ->
                out += GaugeMetric("overbound_players_unique", mapOf("window" to window), value.get())
            }
            retention.forEach { (cohort, ratio) ->
                out += GaugeMetric("overbound_player_retention_ratio", mapOf("cohort" to cohort), ratio)
            }
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

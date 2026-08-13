package io.shulkermc.server.paper

import dev.cubxity.plugins.metrics.bukkit.bootstrap.UnifiedMetricsBukkitBootstrap
import io.shulkermc.server.ShulkerServerAgentCommon

// EXTENDS THE UNIFIEDMETRICS BOOTSTRAP RATHER THAN JavaPlugin, so this one jar
// is both the Shulker agent and the metrics exporter.
//
// The alternative was a second plugin jar in every zone's `plugins` channel.
// That means a second artifact to publish, digest and promote, a second thing
// that can be missing from one environment, and metrics whose presence depends
// on content rather than on the agent -- so a server could come up healthy and
// invisible. Embedding makes "the agent is running" and "the server is being
// scraped" the same fact.
//
// UnifiedMetricsBukkitBootstrap IS a JavaPlugin, so nothing about Shulker's own
// lifecycle changes. It had to be made `open` upstream (our fork): every Bukkit
// collector takes the concrete bootstrap type, because they need the plugin
// handle for the scheduler and the server.
@Suppress("unused")
class ShulkerServerAgentPaper : UnifiedMetricsBukkitBootstrap() {
    private val agent = ShulkerServerAgentCommon(ServerInterfacePaper(this), this.getLogger())

    override fun onEnable() {
        // METRICS FIRST, and deliberately.
        //
        // The agent's initialisation is what marks the GameServer Ready, and it
        // can fail -- that is the failure most worth having a graph of. Starting
        // the exporter first means a server that dies during agent startup is
        // still scraped for the seconds it lives, instead of being invisible
        // exactly when something is wrong.
        //
        // It cannot take the server down with it: UnifiedMetrics catches its own
        // driver failures, and a bind failure on the metrics port is logged
        // rather than thrown.
        this.enableUnifiedMetrics()
        this.agent.onServerInitialization()
    }

    override fun onDisable() {
        // Reverse order: the agent drains players and flushes state, and that
        // is worth measuring, so the exporter outlives it.
        this.agent.onServerShutdown()
        this.disableUnifiedMetrics()
    }
}

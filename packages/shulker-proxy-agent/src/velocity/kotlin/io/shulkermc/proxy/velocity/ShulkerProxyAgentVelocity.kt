package io.shulkermc.proxy.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.PluginDescription
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import dev.cubxity.plugins.metrics.velocity.bootstrap.UnifiedMetricsVelocityBootstrap
import io.shulkermc.proxy.ShulkerProxyAgentCommon
import io.shulkermc.proxy.VelocityBuildConfig
import io.shulkermc.proxy.velocity.commands.GlobalControlCommand
import io.shulkermc.proxy.velocity.commands.GlobalFindCommand
import io.shulkermc.proxy.velocity.commands.GlobalListCommand
import io.shulkermc.proxy.velocity.commands.GlobalTeleportCommand
import java.nio.file.Path
import java.util.logging.Logger

@Plugin(
    id = "shulker-proxy-agent",
    name = "ShulkerProxyAgent",
    version = VelocityBuildConfig.VERSION,
    authors = ["Jérémy Levilain <jeremy@jeremylvln.fr>"],
)
class ShulkerProxyAgentVelocity
    @Inject
    constructor(
        val proxy: ProxyServer,
        logger: Logger,
        @DataDirectory dataDirectory: Path,
        slf4jLogger: org.slf4j.Logger,
        // INJECTED, not looked up. UnifiedMetrics reads a version off this, and
        // the obvious way to get one -- pluginManager.getPlugin(id) -- returns
        // empty here: this constructor runs DURING plugin loading, before this
        // plugin is registered, so the lookup threw NoSuchElementException and
        // Velocity refused to create the plugin at all. Velocity injects
        // PluginDescription directly and that is available immediately.
        pluginDescription: PluginDescription,
    ) {
        val agent = ShulkerProxyAgentCommon(ProxyInterfaceVelocity(this, proxy), logger)

        // UnifiedMetrics, embedded rather than shipped as a second plugin -- same
        // reasoning as the Paper agent.
        //
        // COMPOSED, NOT SUBCLASSED, because Velocity's plugin model is annotation
        // and injection based rather than inheritance based: there is exactly one
        // @Plugin class and it is this one. UnifiedMetricsVelocityBootstrap is an
        // ordinary class with a public constructor whose lifecycle methods are
        // public, so it can simply be built here and handed the events this class
        // already receives. That is why Velocity needs no fork and Bukkit did.
        //
        // eventOwner is this class, and it is load-bearing. UnifiedMetrics'
        // EventsCollection registers a Velocity listener, and Velocity resolves
        // a listener's owner by instance -- the bootstrap above was built with
        // `new` here, so Velocity has no container for it and registration
        // threw "UnifiedMetricsVelocityBootstrap does not have a container".
        // The failure was swallowed into one WARN and the minecraft_events_*
        // counters simply never existed. This class IS the @Plugin, so it is
        // the owner those listeners belong to.
        private val metrics =
            UnifiedMetricsVelocityBootstrap(dataDirectory, proxy, slf4jLogger, pluginDescription)
                .also { it.eventOwner = this }

        @Subscribe
        fun onProxyInitialization(
            event: ProxyInitializeEvent,
        ) {
            // Before the agent, for the reason given in ShulkerServerAgentPaper:
            // agent startup is a thing worth having a graph of when it fails.
            this.metrics.onEnable(event)
            this.agent.onProxyInitialization()

            GlobalListCommand.register(this)
            GlobalTeleportCommand.register(this)
            GlobalFindCommand.register(this)
            GlobalControlCommand.register(this)
        }

        @Subscribe
        fun onProxyShutdown(
            event: ProxyShutdownEvent,
        ) {
            // Reverse order: the agent moves players off first, and that is
            // worth measuring, so the exporter outlives it.
            this.agent.onProxyShutdown()
            this.metrics.onDisable(event)
        }
    }

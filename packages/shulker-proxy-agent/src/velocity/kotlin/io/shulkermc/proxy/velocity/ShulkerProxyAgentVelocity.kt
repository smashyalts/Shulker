package io.shulkermc.proxy.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
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
        // PluginDescription is UnifiedMetrics' own, looked up by its plugin id --
        // it reads a version off it. The id is present because the shaded jar
        // still carries UnifiedMetrics' velocity-plugin.json, and this falls back
        // to Shulker's own description when it is not, so a change in how the jar
        // is assembled degrades a version string rather than failing startup.
        private val metrics =
            UnifiedMetricsVelocityBootstrap(
                dataDirectory,
                proxy,
                slf4jLogger,
                proxy.pluginManager.getPlugin("unifiedmetrics")
                    .orElseGet { proxy.pluginManager.getPlugin("shulker-proxy-agent").get() }
                    .description,
            )

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

package io.shulkermc.proxy.services

import com.google.common.base.Preconditions
import com.google.common.base.Suppliers
import io.shulkermc.proxy.Configuration
import io.shulkermc.proxy.ShulkerProxyAgentCommon
import io.shulkermc.proxy.platform.HookPostOrder
import io.shulkermc.proxy.platform.Player
import io.shulkermc.proxy.platform.PlayerPreLoginHookResult
import io.shulkermc.proxy.platform.ProxyPingHookResult
import io.shulkermc.proxy.platform.ServerPreConnectHookResult
import io.shulkermc.proxy.utils.createDisconnectMessage
import net.kyori.adventure.text.format.NamedTextColor
import java.net.InetSocketAddress
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit

class PlayerMovementService(private val agent: ShulkerProxyAgentCommon) {
    companion object {
        private const val LOBBY_TAG = "lobby"
        private const val LIMBO_TAG = "limbo"

        private const val ONLINE_PLAYERS_COUNT_MEMOIZE_SECONDS = 10L
        private const val PLAYER_CAPACITY_COUNT_MEMOIZE_SECONDS = 60L

        /**
         * How many players one lobby is filled to before the next is used.
         *
         * SET THIS BELOW THE BACKEND'S max-players. The proxy cannot see what a
         * backend will accept, so this is configuration rather than discovery;
         * set too high, players are routed at a server that refuses them and
         * the connection fails for a reason nothing here can explain.
         *
         * 0 disables packing and restores the previous random routing. That is
         * the setting to reach for if this ever misbehaves -- it needs no
         * rebuild, only a restart.
         */
        private val PACK_LIMIT: Int =
            System.getenv("SHULKER_LOBBY_PACK_LIMIT")?.toIntOrNull() ?: 0

        private val MSG_NOT_ACCEPTING_PLAYERS =
            createDisconnectMessage(
                "Proxy is not accepting players, try reconnect.",
                NamedTextColor.RED,
            )

        private val MSG_NO_LIMBO_FOUND =
            createDisconnectMessage(
                "No limbo server found, please check your cluster configuration.",
                NamedTextColor.RED,
            )
    }

    private val maxPlayersWithExclusionDelta =
        this.agent.proxyInterface.getPlayerCapacity() - Configuration.PROXY_PLAYER_DELTA_BEFORE_EXCLUSION

    private val onlinePlayerCountSupplier =
        Suppliers.memoizeWithExpiration(
            { this.agent.cluster.cache.countOnlinePlayers() },
            ONLINE_PLAYERS_COUNT_MEMOIZE_SECONDS,
            TimeUnit.SECONDS,
        )
    private val playerCapacityCountSupplier =
        Suppliers.memoizeWithExpiration(
            { this.agent.cluster.cache.countPlayerCapacity() },
            PLAYER_CAPACITY_COUNT_MEMOIZE_SECONDS,
            TimeUnit.SECONDS,
        )

    private var externalClusterAddress: Optional<InetSocketAddress>
    private var isAllocatedByAgones = false
    private var acceptingPlayers = true

    init {
        this.agent.proxyInterface.addProxyPingHook(this::onProxyPing, HookPostOrder.FIRST)
        this.agent.proxyInterface.addPlayerPreLoginHook(this::onPlayerPreLogin, HookPostOrder.FIRST)
        this.agent.proxyInterface.addPlayerLoginHook(this::onPlayerLogin, HookPostOrder.EARLY)
        this.agent.proxyInterface.addPlayerDisconnectHook(this::onPlayerDisconnect, HookPostOrder.LATE)
        this.agent.proxyInterface.addServerPreConnectHook(this::onServerPreConnect, HookPostOrder.EARLY)
        this.agent.proxyInterface.addServerPostConnectHook(this::onServerPostConnect, HookPostOrder.LATE)

        if (Configuration.PROXY_PREFERRED_RECONNECT_ADDRESS.isPresent) {
            this.externalClusterAddress = Configuration.PROXY_PREFERRED_RECONNECT_ADDRESS
            this.agent.logger.info("Using preferred fleet external address: ${this.externalClusterAddress.get()}")
        } else {
            this.agent.logger.info("Proxy will watch fleet's Service to extract external address")

            this.externalClusterAddress = this.agent.cluster.kubernetesGateway.asProxy().getExternalAddress()
            this.externalClusterAddress.ifPresentOrElse({ address ->
                this.agent.logger.info("Updated fleet's external address: $address")
            }, {
                this.agent.logger.warning(
                    "Fleet's external address was not found, the Service may not be ready yet or is not a LoadBalancer",
                )
            })

            this.agent.cluster.kubernetesGateway.asProxy().watchExternalAddressUpdates(this::onExternalAddressUpdate)
        }
    }

    fun setAcceptingPlayers(acceptingPlayers: Boolean) {
        this.acceptingPlayers = acceptingPlayers

        if (acceptingPlayers) {
            this.agent.fileSystem.deleteReadinessLock()
            this.agent.logger.info("Proxy is now accepting players")
        } else {
            this.agent.fileSystem.createReadinessLock()
            this.agent.logger.info("Proxy is no longer accepting players")
        }
    }

    fun reconnectPlayerToCluster(playerId: UUID) {
        Preconditions.checkState(
            this.externalClusterAddress.isPresent,
            "This ProxyFleet is not operating under a LoadBalancer Service nor have a preferred address configured",
        )

        this.agent.proxyInterface.transferPlayerToAddress(playerId, this.externalClusterAddress.get())
    }

    fun reconnectEveryoneToCluster() {
        Preconditions.checkState(
            this.externalClusterAddress.isPresent,
            "This ProxyFleet is not operating under a LoadBalancer Service nor have a preferred address configured",
        )

        this.agent.proxyInterface.transferEveryoneToAddress(this.externalClusterAddress.get())
    }

    private fun onProxyPing(): ProxyPingHookResult {
        return ProxyPingHookResult(this.onlinePlayerCountSupplier.get(), this.playerCapacityCountSupplier.get())
    }

    private fun onPlayerPreLogin(): PlayerPreLoginHookResult {
        if (!this.acceptingPlayers) {
            return PlayerPreLoginHookResult.Companion.disallow(MSG_NOT_ACCEPTING_PLAYERS)
        }

        return PlayerPreLoginHookResult.Companion.allow()
    }

    private fun onPlayerLogin(player: Player) {
        this.agent.cluster.cache.updateCachedPlayerName(player.uniqueId, player.name)

        if (!this.isAllocatedByAgones) {
            this.isAllocatedByAgones = true
            this.agent.cluster.agonesGateway.setAllocated()
        }

        if (this.acceptingPlayers && this.isProxyConsideredFull()) {
            this.setAcceptingPlayers(false)
        }
    }

    private fun onPlayerDisconnect(player: Player) {
        this.agent.cluster.cache.unsetPlayerPosition(player.uniqueId)

        if (this.isAllocatedByAgones && this.agent.proxyInterface.getPlayerCount() == 0) {
            this.isAllocatedByAgones = false
            this.agent.cluster.agonesGateway.setReady()
        }

        if (!this.acceptingPlayers && !this.agent.proxyLifecycleService.isDraining() && !this.isProxyConsideredFull()) {
            this.setAcceptingPlayers(true)
        }
    }

    /**
     * Chooses a live server carrying [tag].
     *
     * This is both the initial routing decision -- Velocity's `try` list names
     * the placeholder server `lobby`, which lands here -- and the fallback one
     * when a backend a player is already on goes away and Velocity walks `try`
     * again. Both used to take the first entry of the directory's set.
     *
     * Taking the first was wrong twice over. It is not a load balancer: the
     * set is insertion-ordered, so every player on every proxy piled onto
     * whichever lobby registered earliest while the others sat empty. And on
     * the fallback path it is actively harmful, because the server a player is
     * being bounced off is very often still the first one -- the directory
     * only drops it when the Kubernetes DELETE watch event arrives, which
     * races the kick. The player is then sent straight back to the server that
     * just died, that connection fails too, Velocity moves on to `limbo`, and
     * with no limbo in the cluster they are disconnected.
     *
     * Choosing at random fixes the distribution outright and turns the
     * fallback race from "usually the dead one" into a 1-in-n chance that
     * disappears the moment the watch event lands.
     *
     * Filtering on [hasServer] is the belt to that braces: the directory and
     * the proxy's own registry are updated together, so an entry the proxy no
     * longer knows about is one that cannot be connected to anyway.
     *
     * PACKING, and why it is not simply "pick the fullest".
     *
     * Random spreads players evenly, which is correct for load and wrong for a
     * lobby: four hubs with five people each feel dead, while one hub with
     * twenty feels like a server. So the default is now to fill a lobby before
     * opening the next one.
     *
     * TWO THINGS MAKE THAT SAFE, and without them packing reintroduces the bug
     * random was chosen to fix.
     *
     * [exclude] is the server the player is being bounced OFF. On the fallback
     * path the dying backend is very often the FULLEST -- its players have not
     * been dropped yet -- so a naive "most players" would send them straight
     * back to it, the connection would fail, Velocity would walk on to `limbo`,
     * and with no limbo they are disconnected. That is exactly the failure the
     * old "take the first" had.
     *
     * [packLimit] stops a lobby being filled past what the backend will accept.
     * The proxy cannot see a backend's max-players, so the cap is configured
     * rather than discovered; above it the next server is used. Zero disables
     * packing and restores the random behaviour, which is the setting to reach
     * for if this ever misbehaves.
     *
     * The count is this proxy's own view. With several proxies each packs its
     * own players, so the distribution is per-proxy rather than global -- still
     * far denser than random, and it needs no cross-proxy coordination to be
     * correct.
     */
    private fun pickServerByTag(
        tag: String,
        exclude: String? = null,
    ): String? {
        val candidates =
            this.agent.serverDirectoryService.getServersByTag(tag)
                .filter { this.agent.proxyInterface.hasServer(it) }
                .filter { it != exclude }

        if (candidates.isEmpty()) {
            // Everything is excluded, so the server being left is the only one
            // there is. Better to try it than to drop the player.
            return this.agent.serverDirectoryService.getServersByTag(tag)
                .filter { this.agent.proxyInterface.hasServer(it) }
                .randomOrNull()
        }

        if (PACK_LIMIT <= 0) {
            return candidates.randomOrNull()
        }

        // Fullest first, but only among those with room. Ties break randomly so
        // two simultaneous joins do not deterministically land together on a
        // server that has exactly one slot left.
        val withRoom =
            candidates
                .map { it to this.agent.proxyInterface.getServerPlayerCount(it) }
                .filter { (_, count) -> count < PACK_LIMIT }

        if (withRoom.isEmpty()) {
            // Every lobby is at the cap. The autoscaler will add one shortly;
            // until then spreading is better than refusing.
            return candidates.randomOrNull()
        }

        val fullest = withRoom.maxOf { (_, count) -> count }
        return withRoom.filter { (_, count) -> count == fullest }.random().first
    }

    private fun onServerPreConnect(
        player: Player,
        originalServerName: String,
    ): ServerPreConnectHookResult {
        if (originalServerName == LOBBY_TAG) {
            // Exclude whatever they are on: on the fallback path that is the
            // backend that just died, and it is often still the fullest.
            val leaving = this.agent.proxyInterface.getPlayerServerName(player.uniqueId)
            val lobbyServer = this.pickServerByTag(LOBBY_TAG, leaving)
            if (lobbyServer != null) {
                return ServerPreConnectHookResult(true, Optional.of(lobbyServer))
            }

            return this.onServerPreConnect(player, LIMBO_TAG)
        }

        if (originalServerName == LIMBO_TAG) {
            val limboServer = this.pickServerByTag(LIMBO_TAG)
            if (limboServer != null) {
                return ServerPreConnectHookResult(true, Optional.of(limboServer))
            }

            player.disconnect(MSG_NO_LIMBO_FOUND)
            return ServerPreConnectHookResult(false, Optional.empty())
        }

        return ServerPreConnectHookResult(true, Optional.empty())
    }

    private fun onServerPostConnect(
        player: Player,
        serverName: String,
    ) {
        this.agent.cluster.cache.setPlayerPosition(player.uniqueId, this.agent.cluster.selfReference.name, serverName)
    }

    private fun onExternalAddressUpdate(address: Optional<InetSocketAddress>) {
        if (address.isPresent && (this.externalClusterAddress.isEmpty || address.get() != this.externalClusterAddress.get())) {
            this.agent.logger.info("Updated fleet's external address: ${address.get()}")
        } else if (address.isEmpty && this.externalClusterAddress.isPresent) {
            this.agent.logger.warning(
                "Fleet external address was removed, transfer capabilities will be disabled",
            )
        }

        this.externalClusterAddress = address
    }

    private fun isProxyConsideredFull(): Boolean {
        return this.agent.proxyInterface.getPlayerCount() >= this.maxPlayersWithExclusionDelta
    }
}

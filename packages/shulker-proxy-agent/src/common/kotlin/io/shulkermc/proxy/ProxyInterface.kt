package io.shulkermc.proxy

import io.shulkermc.proxy.platform.HookPostOrder
import io.shulkermc.proxy.platform.PlayerDisconnectHook
import io.shulkermc.proxy.platform.PlayerLoginHook
import io.shulkermc.proxy.platform.PlayerPreLoginHook
import io.shulkermc.proxy.platform.ProxyPingHook
import io.shulkermc.proxy.platform.ServerPostConnectHook
import io.shulkermc.proxy.platform.ServerPreConnectHook
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

@Suppress("TooManyFunctions")
interface ProxyInterface {
    fun registerServer(
        name: String,
        address: InetSocketAddress,
    )

    fun unregisterServer(name: String): Boolean

    fun hasServer(name: String): Boolean

    fun addProxyPingHook(
        hook: ProxyPingHook,
        postOrder: HookPostOrder,
    )

    fun addPlayerPreLoginHook(
        hook: PlayerPreLoginHook,
        postOrder: HookPostOrder,
    )

    fun addPlayerLoginHook(
        hook: PlayerLoginHook,
        postOrder: HookPostOrder,
    )

    fun addPlayerDisconnectHook(
        hook: PlayerDisconnectHook,
        postOrder: HookPostOrder,
    )

    fun addServerPreConnectHook(
        hook: ServerPreConnectHook,
        postOrder: HookPostOrder,
    )

    fun addServerPostConnectHook(
        hook: ServerPostConnectHook,
        postOrder: HookPostOrder,
    )

    fun prepareNetworkAdminsPermissions(playerIds: List<UUID>)

    fun teleportPlayerOnServer(
        playerId: UUID,
        serverName: String,
    )

    fun getPlayerCount(): Int

    /**
     * How many players this proxy currently has on [serverName].
     *
     * Needed to fill one lobby before spreading into the next. A proxy only
     * knows about its OWN players, so with several proxies this is a partial
     * view of a backend's occupancy -- good enough to pack, and deliberately
     * not treated as authoritative anywhere.
     *
     * Returns 0 for a server this proxy does not know, which is the same answer
     * as an empty one and is the safe direction: an unknown server sorts last
     * and is chosen only when nothing else is available.
     */
    fun getServerPlayerCount(serverName: String): Int

    /**
     * The server this player is currently on, if any.
     *
     * Used to EXCLUDE it when choosing a fallback. On that path the backend a
     * player is being bounced off is often still the fullest -- its players
     * have not been dropped yet -- so without this, packing would send them
     * straight back to the server that just died.
     */
    fun getPlayerServerName(playerId: UUID): String?

    fun getPlayerCapacity(): Int

    fun transferPlayerToAddress(
        playerId: UUID,
        address: InetSocketAddress,
    )

    fun transferEveryoneToAddress(address: InetSocketAddress)

    fun scheduleDelayedTask(
        delay: Long,
        timeUnit: TimeUnit,
        runnable: Runnable,
    ): ScheduledTask

    fun scheduleRepeatingTask(
        delay: Long,
        interval: Long,
        timeUnit: TimeUnit,
        runnable: Runnable,
    ): ScheduledTask

    interface ScheduledTask {
        fun cancel()
    }
}

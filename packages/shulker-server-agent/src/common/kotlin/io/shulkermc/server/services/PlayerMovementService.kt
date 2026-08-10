package io.shulkermc.server.services

import io.shulkermc.server.Configuration
import io.shulkermc.server.ShulkerServerAgentCommon
import io.shulkermc.server.platform.HookPostOrder

class PlayerMovementService(private val agent: ShulkerServerAgentCommon) {
    companion object {
        /**
         * Name of the Agones counter tracking live players.
         *
         * Must match the counter the operator declares on every GameServer it
         * builds (`shulker-operator`'s `constants::PLAYERS_COUNTER`), because
         * the SDK can only update counters that the spec declares.
         */
        const val PLAYERS_COUNTER = "players"
    }

    init {
        this.agent.serverInterface.addPlayerJoinHook(this::onPlayerJoin, HookPostOrder.MONITOR)
        this.agent.serverInterface.addPlayerQuitHook(this::onPlayerQuit, HookPostOrder.MONITOR)
    }

    private fun onPlayerJoin() {
        this.updateAllocationState(triggerFromJoin = true)
        this.reportPlayerCount(triggerFromJoin = true)
    }

    private fun onPlayerQuit() {
        this.updateAllocationState(triggerFromJoin = false)
        this.reportPlayerCount(triggerFromJoin = false)
    }

    private fun updateAllocationState(triggerFromJoin: Boolean) {
        if (Configuration.LIFECYCLE_STRATEGY !== Configuration.LifecycleStrategy.ALLOCATE_WHEN_NOT_EMPTY) {
            return
        }

        val playerCount = this.agent.serverInterface.getPlayerCount()

        if (triggerFromJoin && playerCount == 1) {
            this.agent.cluster.agonesGateway.setAllocated()
        } else if (!triggerFromJoin && playerCount == 1) {
            this.agent.cluster.agonesGateway.setReady()
        }
    }

    /**
     * Keeps the Agones `players` counter in step with the live player count, so
     * a Counter fleet autoscaler can scale on real free slots.
     *
     * Without this the only signal Agones has is Ready versus Allocated, which
     * cannot tell one player apart from a full server -- so a fleet of
     * half-empty servers looks exactly as busy as a saturated one.
     */
    private fun reportPlayerCount(triggerFromJoin: Boolean) {
        // The quit hook fires before the player is removed from the server's
        // own list, so on the way out the leaving player is still counted.
        val playerCount = this.agent.serverInterface.getPlayerCount()
        val liveCount = if (triggerFromJoin) playerCount else playerCount - 1

        this.agent.cluster.agonesGateway
            .alpha()
            .setCounter(PLAYERS_COUNTER, liveCount.toLong())
            .exceptionally { error ->
                // Agones rejects counter updates when the CountsAndLists
                // feature gate is off, or when the counter is not declared on
                // the GameServer. Neither should stop a player joining, so log
                // once and carry on -- the fleet just falls back to whatever
                // its Buffer policy does.
                this.agent.logger.warning(
                    "Failed to report the player count to Agones: ${error.message}. " +
                        "Counter-based autoscaling will not work; check that the " +
                        "CountsAndLists feature gate is enabled.",
                )
                null
            }
    }
}

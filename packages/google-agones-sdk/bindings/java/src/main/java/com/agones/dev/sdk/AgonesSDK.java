package com.agones.dev.sdk;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AgonesSDK {
    void destroy();

    CompletableFuture<GameServer> getGameServer();
    CompletableFuture<String> getState();

    CompletableFuture<Void> setReady();
    CompletableFuture<Void> setAllocated();
    CompletableFuture<Void> setReserved(long seconds);
    void askShutdown();

    void sendHealthcheck();

    Alpha alpha();
    interface Alpha {
        CompletableFuture<Boolean> notifyPlayerConnected(String id);
        CompletableFuture<Boolean> notifyPlayerDisconnected(String id);
        CompletableFuture<Void> setPlayerCapacity(long capacity);
        CompletableFuture<Long> getPlayerCapacity();
        CompletableFuture<Long> getPlayerCount();
        CompletableFuture<Boolean> isPlayerConnected(String id);
        CompletableFuture<List<String>> getConnectedPlayers();

        /**
         * Sets a Counter to an absolute value.
         * <br>
         * Counters are the supported way to expose per-GameServer capacity to
         * Agones; the player-tracking methods above are the older mechanism and
         * are gated behind a different feature flag. A Counter fleet autoscaler
         * scales on the aggregate spare capacity of a named counter, which is
         * finer-grained than counting whole Ready servers.
         * <br>
         * The counter must be declared in the GameServer spec; updating an
         * undeclared counter fails.
         *
         * @param name Name of the counter, as declared on the GameServer
         * @param count Absolute value to set
         */
        /** @deprecated UNIMPLEMENTED on Agones >= 1.53. Use {@link Beta#setCounter}. */
        @Deprecated
        CompletableFuture<Void> setCounter(String name, long count);

        /**
         * Reads the current value of a Counter.
         *
         * @param name Name of the counter, as declared on the GameServer
         */
        /** @deprecated UNIMPLEMENTED on Agones >= 1.53. Use {@link Beta#getCounter}. */
        @Deprecated
        CompletableFuture<Long> getCounter(String name);

        /**
         * Sets the maximum capacity of a Counter. This is the denominator a
         * Counter fleet autoscaler measures spare capacity against.
         *
         * @param name Name of the counter, as declared on the GameServer
         * @param capacity Maximum the count may reach
         */
        /** @deprecated UNIMPLEMENTED on Agones >= 1.53. Use {@link Beta#setCounterCapacity}. */
        @Deprecated
        CompletableFuture<Void> setCounterCapacity(String name, long capacity);
    }

    /**
     * Counters, on the BETA service.
     * <br>
     * Not {@link Alpha}. Agones graduated Counters and Lists out of alpha: as of
     * 1.59 the alpha service does not serve {@code UpdateCounter} at all, and a
     * call to it fails with {@code UNIMPLEMENTED: unknown method UpdateCounter
     * for service agones.dev.sdk.alpha.SDK}. That error names the method, not
     * the feature gate, so it is easy to misread as CountsAndLists being
     * disabled -- check the service before the gate.
     */
    Beta beta();
    interface Beta {
        /**
         * Sets a Counter to an absolute value.
         * <br>
         * The counter must be declared in the GameServer spec; updating an
         * undeclared counter fails. A Counter fleet autoscaler scales on the
         * aggregate spare capacity of a named counter, which is finer-grained
         * than counting whole Ready servers.
         *
         * @param name Name of the counter, as declared on the GameServer
         * @param count Absolute value to set
         */
        CompletableFuture<Void> setCounter(String name, long count);

        /**
         * Reads the current value of a Counter.
         *
         * @param name Name of the counter, as declared on the GameServer
         */
        CompletableFuture<Long> getCounter(String name);

        /**
         * Sets the maximum capacity of a Counter. This is the denominator a
         * Counter fleet autoscaler measures spare capacity against.
         *
         * @param name Name of the counter, as declared on the GameServer
         * @param capacity Maximum the count may reach
         */
        CompletableFuture<Void> setCounterCapacity(String name, long capacity);
    }
}

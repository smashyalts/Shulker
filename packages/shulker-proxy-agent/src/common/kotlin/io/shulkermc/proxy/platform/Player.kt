package io.shulkermc.proxy.platform

import net.kyori.adventure.text.Component
import java.util.UUID

interface Player {
    val uniqueId: UUID
    val name: String

    /**
     * The hostname the client actually typed to reach us, or null when the
     * platform cannot tell us (or the client connected straight to an IP).
     *
     * ATTACKER-CONTROLLED. This is a field of the client's handshake packet and
     * nothing validates it -- a client may send any string it likes, and a
     * different one on every connection. It must never reach a metric label
     * unfiltered; PlayerAnalyticsService maps it through an allow-list for
     * exactly that reason.
     *
     * Already cleaned of the two things that are not part of the hostname: the
     * port, and everything from the first NUL onwards. Forge clients append
     * NUL + "FML" + NUL and proxies append their forwarding payload the same
     * way, so a raw handshake address often carries a trailer.
     */
    val virtualHost: String?

    fun disconnect(component: Component)
}

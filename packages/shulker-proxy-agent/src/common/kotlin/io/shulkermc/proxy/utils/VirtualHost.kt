package io.shulkermc.proxy.utils

/**
 * The separator between the hostname and the handshake trailer.
 *
 * Built with Char(0) rather than written as an escape on purpose: a literal NUL
 * in a source file is invisible in review and makes several tools -- grep
 * included -- treat the file as binary.
 */
private val HANDSHAKE_TRAILER_SEPARATOR = Char(0)

/**
 * Reduce a handshake address to just the hostname.
 *
 * The value arrives from the client and carries two things that are not part
 * of the name:
 *
 *   - a trailer after a NUL. Forge sends NUL + "FML"/"FML2" + NUL, and both
 *     BungeeCord and Velocity IP-forwarding append their payload the same way,
 *     so the raw string is regularly longer than the hostname it starts with.
 *   - a port, when a host:port string reaches this rather than a bare name.
 *
 * Case is folded and a trailing dot (the DNS root, which resolves the same and
 * some clients send) is dropped, so "Play.Example.Com." and "play.example.com"
 * are one value rather than three.
 *
 * Returns null for anything empty, so callers have a single "no hostname" case
 * rather than having to treat null and "" separately.
 */
fun cleanVirtualHost(raw: String?): String? {
    if (raw.isNullOrEmpty()) return null

    var host = raw.substringBefore(HANDSHAKE_TRAILER_SEPARATOR).trim()

    // Strip a port only when there is exactly one colon. An IPv6 literal has
    // several, and chopping at the first would turn "::1" into "" -- which
    // would read as "no hostname" rather than as the odd-but-real value it is.
    if (host.count { it == ':' } == 1) {
        host = host.substringBefore(':')
    }

    return host
        .removeSuffix(".")
        .lowercase()
        .ifEmpty { null }
}

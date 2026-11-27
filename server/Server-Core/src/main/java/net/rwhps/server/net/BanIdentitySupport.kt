package net.rwhps.server.net

import net.rwhps.server.util.IpUtils

internal object BanIdentitySupport {
    /** Legacy blacklist storage uses IpUtils' encoded network key, not a dotted prefix. */
    fun subnetKey(raw: String): String? {
        val value = raw.trim()
        if (value.startsWith("[IPV4]-") || value.startsWith("[IPV6]-")) return value
        if ('/' in value && !value.endsWith("/24")) return null
        val address = value.removeSuffix("/24").let { if (it.count { char -> char == '.' } == 2) "$it.0" else it }
        return runCatching { IpUtils.ipToLong24(address, false) }.getOrNull()
    }
}

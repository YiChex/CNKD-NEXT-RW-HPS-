package net.rwhps.server.util

import org.junit.jupiter.api.Test

/**
 *
 *
 * @date 2024/1/18 20:51
 * @author Dr (dr@der.kim)
 */
class IpUtilsTest {

    @Test
    fun test() {
        //V4
        IpUtils.ipToLong24("1.1.1.1", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        IpUtils.ipToLong24("192.168.0.0", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        IpUtils.ipToLong24("127.0.0.1", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        IpUtils.ipToLong24("0.0.0.0", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        IpUtils.ipToLong24("1.1.1.1").let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        //V6
        IpUtils.ipToLong24("2001:0410:0000:1234:FB00:1400:5000:45FF", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        IpUtils.ipToLong24("FE80:0000:0000:0000:0000:0000:0000:0009", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        IpUtils.ipToLong24("0000:0000:0000:0000:0000:0000:0000:0000", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        //
        IpUtils.ipToLong24("2001:0410::FB00:1400:5000:45FF", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
//        IpUtils.ipToLong24("3ffe::1010:2a2a::0001", false).let {
//            println(it)
//            println(IpUtils.longToIp(it))
//        }
        IpUtils.ipToLong24("::", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        //
        IpUtils.ipToLong24("2001:410:0:1234:FB00:1400:5000:45FF", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        IpUtils.ipToLong24("0:0:0:0:0:0:0:0", false).let {
            println(it)
            println(IpUtils.longToIp(it))
        }
        //
//        IpUtils.ipToLong24("0:0:0:0:0:0:138.1.1.1", false).let {
//            println(it)
//            println(IpUtils.longToIp(it))
//        }
//        IpUtils.ipToLong24("0:0:0:0:0:0:138.1.1.1").let {
//            println(it)
//            println(IpUtils.longToIp(it))
//        }
        IpUtils.ipToLong24("2001:410:0:1234:FB00:1400:5000:45FF").let {
            println(it)
            println(IpUtils.longToIp(it))
        }
    }

    @Test
    fun ipToLong24() {
    }

    @Test
    fun longToIp() {
    }
}
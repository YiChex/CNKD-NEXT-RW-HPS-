/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.math

import java.util.*
import kotlin.math.min

/**
 * 此类实现了xorshift128 +算法，该算法是一种非常快速的高质量64位伪随机数生成器
 * 该PRNG的质量比[Random]的质量高得多，并且其循环长度为2 128 -1
 * 这对于任何单线程应用程序来说已经足够了
 * RandomXS128的实例不是线程安全的。
 * @author Inferno
 * @author davebaol
 */
class Rand: Random {
    /** 这个伪随机数发生器内部状态的前半部分.  */
    private var seed0: Long = 0

    /** The second half of the internal state of this pseudo-random number generator.  */
    private var seed1: Long = 0

    /**
     * Creates a new random number generator. This constructor sets the seed of the random number generator to a value very likely
     * to be distinct from any other invocation of this constructor.
     *
     *
     * This implementation creates a [Random] instance to generate the initial seed.
     */
    constructor() {
        setSeed(Random().nextLong())
    }

    /**
     * Creates a new random number generator using a single `long` seed.
     * @param seed the initial seed
     */
    constructor(seed: Long) {
        setSeed(seed)
    }

    /**
     * Creates a new random number generator using two `long` seeds.
     * @param seed0 the first part of the initial seed
     * @param seed1 the second part of the initial seed
     */
    constructor(seed0: Long, seed1: Long) {
        setState(seed0, seed1)
    }

    /**
     * Returns the next pseudo-random, uniformly distributed `long` value from this random number generator's sequence.
     *
     *
     * Subclasses should override this, as this is used by all other methods.
     */
    fun nextLongNew(): Long {
        var s1 = seed0
        val s0 = seed1
        seed0 = s0
        s1 = s1 xor (s1 shl 23)
        return (s1 xor s0 xor (s1 ushr 17) xor (s0 ushr 26)).also { seed1 = it } + s0
    }

    /** This protected method is final because, contrary to the superclass, it's not used anymore by the other methods.  */
    override fun next(bits: Int): Int {
        return (nextLongNew() and (1L shl bits) - 1).toInt()
    }

    /**
     * Returns the next pseudo-random, uniformly distributed `int` value from this random number generator's sequence.
     *
     *
     * This implementation uses [.nextLong] internally.
     */
    override fun nextInt(): Int {
        return nextLongNew().toInt()
    }

    /**
     * Returns a pseudo-random, uniformly distributed `int` value between 0 (inclusive) and the specified value (exclusive),
     * drawn from this random number generator's sequence.
     *
     *
     * This implementation uses [.nextLong] internally.
     * @param n the positive bound on the random number to be returned.
     * @return the next pseudo-random `int` value between `0` (inclusive) and `n` (exclusive).
     */
    override fun nextInt(n: Int): Int {
        return nextLongNew(n.toLong()).toInt()
    }

    /**
     * Returns a pseudo-random, uniformly distributed `long` value between 0 (inclusive) and the specified value (exclusive),
     * drawn from this random number generator's sequence. The algorithm used to generate the value guarantees that the result is
     * uniform, provided that the sequence of 64-bit values produced by this generator is.
     *
     *
     * This implementation uses [.nextLong] internally.
     * @param n the positive bound on the random number to be returned.
     * @return the next pseudo-random `long` value between `0` (inclusive) and `n` (exclusive).
     */
    fun nextLongNew(n: Long): Long {
        require(n > 0) { "n must be positive" }
        while (true) {
            val bits = nextLongNew() ushr 1
            val value = bits % n
            if (bits - value + (n - 1) >= 0) {
                return value
            }
        }
    }

    /**
     * Returns a pseudo-random, uniformly distributed `double` value between 0.0 and 1.0 from this random number generator's
     * sequence.
     *
     *
     * This implementation uses [.nextLong] internally.
     */
    override fun nextDouble(): Double {
        return (nextLongNew() ushr 11) * NORM_DOUBLE
    }

    /**
     * Returns a pseudo-random, uniformly distributed `float` value between 0.0 and 1.0 from this random number generator's
     * sequence.
     *
     *
     * This implementation uses [.nextLong] internally.
     */
    override fun nextFloat(): Float {
        return ((nextLongNew() ushr 40) * NORM_FLOAT).toFloat()
    }

    /**
     * Returns a pseudo-random, uniformly distributed `boolean ` value from this random number generator's sequence.
     *
     *
     * This implementation uses [.nextLong] internally.
     */
    override fun nextBoolean(): Boolean {
        return nextLongNew() and 1 != 0L
    }

    /**
     * Generates random bytes and places them into a user-supplied byte array. The number of random bytes produced is equal to the
     * length of the byte array.
     *
     *
     * This implementation uses [.nextLong] internally.
     */
    override fun nextBytes(bytes: ByteArray) {
        var n: Int
        var i = bytes.size
        while (i != 0) {
            n = min(i, 8)
            var bits = nextLongNew()
            while (n-- != 0) {
                bytes[--i] = bits.toByte()
                bits = bits shr 8
            }
        }
    }

    /**
     * Sets the internal seed of this generator based on the given `long` value.
     *
     *
     * The given seed is passed twice through a hash function. This way, if the user passes a small value we avoid the short
     * irregular transient associated with states having a very small number of bits set.
     * @param seed a nonzero seed for this generator (if zero, the generator will be seeded with [Long.MIN_VALUE]).
     */
    override fun setSeed(seed: Long) {
        val seed0 = murmurHash3(if (seed == 0L) Long.MIN_VALUE else seed)
        setState(seed0, murmurHash3(seed0))
    }

    fun chance(chance: Double): Boolean {
        return nextDouble() < chance
    }

    fun range(amount: Float): Float {
        return nextFloat() * amount * 2 - amount
    }

    fun random(max: Float): Float {
        return nextFloat() * max
    }

    fun random(min: Float, max: Float): Float {
        return min + (max - min) * nextFloat()
    }

    fun range(amount: Int): Int {
        return nextInt(amount * 2 + 1) - amount
    }

    fun random(min: Int, max: Int): Int {
        return min + nextInt(max - min + 1)
    }

    /**
     * 设置此生成器的内部状态.
     * @param seed0 内部状态的第一部分
     * @param seed1 内部状态的第二部分
     */
    fun setState(seed0: Long, seed1: Long) {
        this.seed0 = seed0
        this.seed1 = seed1
    }

    /**
     * Returns the internal seeds to allow state saving.
     * @param seed must be 0 or 1, designating which of the 2 long seeds to return
     * @return the internal seed that can be used in setState
     */
    fun getState(seed: Int): Long {
        return if (seed == 0) seed0 else seed1
    }

    companion object {
        /** 归一化常数为double  */
        private const val NORM_DOUBLE = 1.0 / (1L shl 53)

        /** 浮点数的归一化常数  */
        private const val NORM_FLOAT = 1.0 / (1L shl 24)
        private fun murmurHash3(xIn: Long): Long {
            var x = xIn
            x = x xor (x ushr 33)
            x *= -0xae502812aa7333L
            x = x xor (x ushr 33)
            x *= -0x3b314601e57a13adL
            x = x xor (x ushr 33)
            return x
        }
    }
}
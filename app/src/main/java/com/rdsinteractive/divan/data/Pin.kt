package com.rdsinteractive.divan.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Parental PIN: a sequence of remote arrows (U, D, L, R) and digits. Only a salted
 * PBKDF2 hash is stored; the PIN itself never touches the disk.
 */
object Pin {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    private const val ITERATIONS = 20_000

    data class Hashed(val salt: String, val hash: String)

    fun hash(pin: String): Hashed {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Hashed(encode(salt), encode(derive(pin, salt)))
    }

    fun matches(pin: String, salt: String, hash: String): Boolean {
        val expected = runCatching { decode(hash) to decode(salt) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, expected.second), expected.first)
    }

    /** What the user sees for a PIN symbol while setting it. */
    fun symbol(char: Char): String = when (char) {
        'U' -> "↑"
        'D' -> "↓"
        'L' -> "←"
        'R' -> "→"
        else -> char.toString()
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
}

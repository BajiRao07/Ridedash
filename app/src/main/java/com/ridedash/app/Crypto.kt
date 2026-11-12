package com.ridedash.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.content.Context
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptography for the phone-to-phone link.
 *
 * Pairing  : ECDH P-256 key exchange with a commitment, confirmed by the user
 *            comparing a 6-digit code on both screens (like Bluetooth numeric
 *            comparison). An attacker in the middle has a 1 in 1,000,000 chance.
 * Sessions : fresh random nonces per connection, per-direction AES-256-GCM keys
 *            derived from the paired key, counters reject replayed frames.
 */
object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }

    fun newKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), rng)
        return g.generateKeyPair()
    }

    fun publicFrom(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    /** 6-digit code both users compare. */
    fun sas(transcript: ByteArray): String {
        val h = sha256("ridedash-sas".toByteArray(), transcript)
        val v = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xffffffffL
        return String.format(Locale.US, "%06d", v % 1_000_000L)
    }

    fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** Encrypted, replay-protected channel for one connection. */
    class Channel(
        sendKey: ByteArray,
        recvKey: ByteArray,
        private val sendLabel: ByteArray,
        private val recvLabel: ByteArray
    ) {
        private val sk = SecretKeySpec(sendKey.copyOf(32), "AES")
        private val rk = SecretKeySpec(recvKey.copyOf(32), "AES")
        private var sendCtr = 0L
        private var recvCtr = 0L

        @Synchronized
        fun seal(plain: ByteArray): ByteArray {
            sendCtr++
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(128, nonce(sendCtr)))
            c.updateAAD(sendLabel)
            val ct = c.doFinal(plain)
            return ByteBuffer.allocate(8 + ct.size).putLong(sendCtr).put(ct).array()
        }

        @Synchronized
        fun open(frame: ByteArray): ByteArray {
            if (frame.size < 8 + 16) throw GeneralSecurityException("frame too short")
            val bb = ByteBuffer.wrap(frame)
            val ctr = bb.long
            if (ctr <= recvCtr) throw GeneralSecurityException("replayed frame")
            val ct = ByteArray(frame.size - 8)
            bb.get(ct)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, rk, GCMParameterSpec(128, nonce(ctr)))
            c.updateAAD(recvLabel)
            val plain = c.doFinal(ct) // throws if tampered
            recvCtr = ctr
            return plain
        }

        private fun nonce(ctr: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(ctr).array()
    }
}

/** Stores the paired link key, wrapped by the Android Keystore where available (Android 6+). */
object SecureStore {
    private const val ALIAS = "ridedash_wrap_v1"
    private fun prefs(c: Context) = c.getSharedPreferences("ridedash_secure", Context.MODE_PRIVATE)

    fun saveKey(c: Context, key: ByteArray) {
        val stored = if (Build.VERSION.SDK_INT >= 23) {
            try { "v1:" + wrap(key) } catch (e: Exception) { "v0:" + Crypto.b64(key) }
        } else "v0:" + Crypto.b64(key)
        prefs(c).edit().putString("link_key", stored).apply()
    }

    fun loadKey(c: Context): ByteArray? {
        val s = prefs(c).getString("link_key", null) ?: return null
        return try {
            when {
                s.startsWith("v1:") && Build.VERSION.SDK_INT >= 23 -> unwrap(s.substring(3))
                s.startsWith("v0:") -> Crypto.unb64(s.substring(3))
                else -> null
            }
        } catch (e: Exception) { null }
    }

    fun clear(c: Context) = prefs(c).edit().clear().apply()

    @RequiresApi(23)
    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    @RequiresApi(23)
    private fun wrap(key: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        val iv = c.iv
        val ct = c.doFinal(key)
        return Crypto.b64(byteArrayOf(iv.size.toByte()) + iv + ct)
    }

    @RequiresApi(23)
    private fun unwrap(s: String): ByteArray {
        val all = Crypto.unb64(s)
        val ivLen = all[0].toInt()
        val iv = all.copyOfRange(1, 1 + ivLen)
        val ct = all.copyOfRange(1 + ivLen, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }
}

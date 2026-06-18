package ai.aureuma.conveltkit

import android.content.SharedPreferences
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

class ConveltUserIdentityResolver(
    private val sharedPreferences: SharedPreferences,
    private val keyPrefix: String,
    private val salt: String,
) {
    fun resolve(externalUserId: String): ConveltUserIdentity {
        val normalized = externalUserId.trim()
        require(normalized.isNotEmpty()) { "externalUserId must not be blank" }

        val key = keyPrefix + normalized
        val existing = sharedPreferences.getString(key, null)
        val customerId = existing?.let(UUID::fromString)
            ?: deterministicCustomerId(normalized, salt).also {
                sharedPreferences.edit().putString(key, it.toString()).apply()
            }
        return ConveltUserIdentity(
            externalUserId = normalized,
            customerId = customerId,
        )
    }

    fun adoptCanonicalCustomerId(externalUserId: String, customerId: UUID): Boolean {
        val normalized = externalUserId.trim()
        require(normalized.isNotEmpty()) { "externalUserId must not be blank" }

        val key = keyPrefix + normalized
        val existing = sharedPreferences.getString(key, null)
        if (existing == customerId.toString()) {
            return false
        }
        sharedPreferences.edit().putString(key, customerId.toString()).apply()
        return true
    }

    companion object {
        fun deterministicCustomerId(externalUserId: String, salt: String): UUID {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$salt:$externalUserId".toByteArray(Charsets.UTF_8))
            val bytes = digest.copyOfRange(0, 16)
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(bytes)
            return UUID(buffer.long, buffer.long)
        }
    }
}

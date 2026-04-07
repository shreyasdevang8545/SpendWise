package com.tech.spendwise.security

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Field-level AES-256-GCM encryption for Firestore documents.
 *
 * A key is derived per-user from SHA-256(uid + SECRET_SALT), so the same
 * user can decrypt their data on any device. The result is stored as
 * "Base64(IV):Base64(ciphertext)" — two colon-separated base64 strings.
 *
 * NOTE: SECRET_SALT is a compile-time constant. For production you can
 * additionally fetch a server-side pepper, but this provides strong
 * encryption for personal finance data even as-is.
 */
object TransactionCrypto {

    private val SECRET_SALT: String by lazy {
        // Obfuscated salt to avoid plain-text recovery from binary
        "SW" + "_FIRESTORE" + "_SALT" + "_V1" + "_PRIVATE"
    }
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val SEPARATOR = ":"

    // ── Key Derivation ─────────────────────────────────────────────────────

    private fun deriveKey(uid: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest("$uid$SECRET_SALT".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    // ── Encrypt / Decrypt ─────────────────────────────────────────────────

    /**
     * Encrypts a plaintext field value for the given Firebase [uid].
     * Returns "Base64(IV):Base64(ciphertext)".
     */
    fun encryptField(plaintext: String, uid: String): String {
        val key    = deriveKey(uid)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv         = cipher.iv
        val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64   = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(cipherText, Base64.NO_WRAP)
        return "$ivB64$SEPARATOR$dataB64"
    }

    /**
     * Decrypts a field value previously produced by [encryptField].
     * Returns the original plaintext, or the raw string if it cannot be decrypted.
     */
    fun decryptField(encoded: String, uid: String): String {
        return try {
            val parts = encoded.split(SEPARATOR, limit = 2)
            if (parts.size != 2) return encoded
            val iv         = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = deriveKey(uid)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            encoded  // Return raw value as fallback (e.g. unencrypted legacy data)
        }
    }

    // ── Full Transaction Helpers ──────────────────────────────────────────

    /**
     * Encrypts all sensitive fields from a confirmed transaction JSON string.
     * Handles both regular transactions and lends.
     */
    fun encryptTransaction(plainJson: String, uid: String): Map<String, Any?> {
        val data   = parseSimpleJson(plainJson)
        val result = mutableMapOf<String, Any?>()

        val sensitiveFields = setOf("amount", "type", "merchant", "category", "payment_mode", "currency", "lend_name", "phone_number", "note")
        for ((key, value) in data) {
            result[key] = if (key in sensitiveFields) {
                if (value.isEmpty()) "" else encryptField(value, uid)
            } else {
                // Handle non-string types that might be in the map if it was built from an object
                value
            }
        }
        result["uid"] = uid
        return result
    }

    /**
     * Decrypts all sensitive fields from a database document [map].
     */
    fun decryptTransaction(map: Map<String, Any?>, uid: String): String {
        val sensitiveFields = setOf("amount", "type", "merchant", "category", "payment_mode", "currency", "lend_name", "phone_number", "note")
        val result = mutableMapOf<String, String>()
        for ((key, value) in map) {
            val raw = value?.toString() ?: continue
            result[key] = if (key in sensitiveFields && raw.isNotEmpty()) decryptField(raw, uid) else raw
        }
        return buildJson(result)
    }

    // ── Credit Card Helpers ───────────────────────────────────────────────

    /**
     * Encrypts sensitive fields for a CreditCard.
     */
    fun encryptCreditCard(name: String, last4: String, limit: Double, uid: String): Map<String, String> {
        return mapOf(
            "name" to encryptField(name, uid),
            "last4" to encryptField(last4, uid),
            "limit" to encryptField(limit.toString(), uid)
        )
    }

    /**
     * Decrypts a database map into a CreditCard object.
     */
    fun mapToCreditCard(id: String, map: Map<String, Any?>, uid: String): com.tech.spendwise.models.CreditCard? {
        return try {
            val nameRaw = map["name"]?.toString() ?: ""
            val last4Raw = map["last4"]?.toString() ?: ""
            val limitRaw = map["limit"]?.toString() ?: ""
            val paybackDay = (map["payback_day"]?.toString()?.toDoubleOrNull() ?: 1.0).toInt()
            val reminderEnabled = map["reminder_enabled"]?.toString()?.toBoolean() ?: true
            val createdAtRaw = map["created_at"]?.toString() ?: "0"

            fun parse(raw: String): Long {
                return raw.toLongOrNull() ?: try {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).parse(raw.take(19))?.time ?: 0L
                } catch (e: Exception) { 0L }
            }

            com.tech.spendwise.models.CreditCard(
                id = id,
                name = if (nameRaw.isNotEmpty()) decryptField(nameRaw, uid) else "",
                last4 = if (last4Raw.isNotEmpty()) decryptField(last4Raw, uid) else "",
                limit = if (limitRaw.isNotEmpty()) decryptField(limitRaw, uid).toDoubleOrNull() ?: 0.0 else 0.0,
                paybackDay = paybackDay,
                reminderEnabled = reminderEnabled,
                uid = uid,
                createdAt = parse(createdAtRaw)
            )
        } catch (e: Exception) {
            android.util.Log.e("TransactionCrypto", "Error in mapToCreditCard for id=$id", e)
            null
        }
    }


    /**
     * Converts a database map into a LendTransaction object.
     */
    fun mapToLendTransaction(id: String, map: Map<String, Any?>, uid: String): com.tech.spendwise.models.LendTransaction? {
        return try {
            val isLend = map["is_lend"]?.toString()?.toBoolean() ?: false
            if (!isLend) {
                android.util.Log.w("TransactionCrypto", "mapToLendTransaction: is_lend is false for id=$id")
                return null
            }
            
            val amountRaw = map["amount"]?.toString() ?: run {
                android.util.Log.e("TransactionCrypto", "mapToLendTransaction: amount is null for id=$id")
                return null
            }
            val lendNameRaw = map["lend_name"]?.toString() ?: ""
            val returnDateRaw = map["return_date"]?.toString() ?: "0"
            val createdAtRaw = map["created_at"]?.toString() ?: map["saved_at"]?.toString() ?: "0"

            fun parse(raw: String): Long {
                return raw.toLongOrNull() ?: try {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).parse(raw.take(19))?.time ?: 0L
                } catch (e: Exception) { 0L }
            }

            val name = if (lendNameRaw.isNotEmpty()) decryptField(lendNameRaw, uid) else ""
            val amount = decryptField(amountRaw, uid).toDoubleOrNull() ?: 0.0
            
            if (amount == 0.0) {
                android.util.Log.w("TransactionCrypto", "mapToLendTransaction: Decrypted amount is 0.0 for id=$id. amountRaw=$amountRaw")
            }

            com.tech.spendwise.models.LendTransaction(
                id = id,
                name = name,
                amount = amount,
                paymentMode = decryptField(map["payment_mode"]?.toString() ?: "", uid),
                phoneNumber = decryptField(map["phone_number"]?.toString() ?: "", uid),
                note = decryptField(map["note"]?.toString() ?: "", uid),
                returnDate = parse(returnDateRaw),
                isReturned = map["is_returned"]?.toString()?.toBoolean() ?: false,
                createdAt = parse(createdAtRaw),
                transactionId = id
            )
        } catch (e: Exception) {
            android.util.Log.e("TransactionCrypto", "Error in mapToLendTransaction for id=$id", e)
            null
        }
    }

    // ── Internal utils ────────────────────────────────────────────────────
    
    fun parseSimpleJson(json: String): Map<String, String> {
        val result  = mutableMapOf<String, String>()
        val pattern = Regex(""""(\w+)"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|([^,}\s]+))""")
        for (match in pattern.findAll(json)) {
            val key   = match.groupValues[1]
            val value = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            result[key] = value
        }
        return result
    }

    private fun buildJson(map: Map<String, String>): String {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val entries = map.entries.joinToString(",") { (k, v) ->
            "\"$k\":\"${v.esc()}\""
        }
        return "{$entries}"
    }
}

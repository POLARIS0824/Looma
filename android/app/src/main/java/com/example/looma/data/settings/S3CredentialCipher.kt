package com.example.looma.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object S3CredentialCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "looma_s3_credentials_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(accessKey: String, secretKey: String, replaceInvalidKey: Boolean): String = try {
        val keyStore = openKeyStore()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey? ?: generateKey()
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (exception: KeyPermanentlyInvalidatedException) {
            if (!replaceInvalidKey) throw exception
            keyStore.deleteEntry(KEY_ALIAS)
            cipher.init(Cipher.ENCRYPT_MODE, generateKey())
        }
        val payload = JSONArray().put(accessKey).put(secretKey).toString().toByteArray(Charsets.UTF_8)
        Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(payload))
    } catch (exception: GeneralSecurityException) {
        throw IOException("Could not encrypt S3 credentials", exception)
    } catch (exception: ProviderException) {
        throw IOException("Could not encrypt S3 credentials", exception)
    } catch (exception: SecurityException) {
        throw IOException("Could not encrypt S3 credentials", exception)
    }

    fun decrypt(encoded: String): Pair<String, String>? = try {
        val bytes = Base64.getDecoder().decode(encoded)
        val key = openKeyStore().getKey(KEY_ALIAS, null) as SecretKey?
        if (key == null || bytes.size < IV_BYTES + TAG_BITS / 8) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            val payload = JSONArray(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).toString(Charsets.UTF_8))
            payload.getString(0) to payload.getString(1)
        }
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: ProviderException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: JSONException) {
        null
    }

    private fun openKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }
        .generateKey()
}

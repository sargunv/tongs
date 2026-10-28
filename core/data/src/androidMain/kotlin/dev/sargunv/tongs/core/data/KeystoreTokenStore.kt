package dev.sargunv.tongs.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android tokens, encrypted with an AES-GCM key that lives in the Android Keystore and never leaves
 * it. Each account's ciphertext is a file in [dir], which should be excluded from backup: the key
 * doesn't move to another device, so a restored file couldn't be decrypted anyway.
 */
class KeystoreTokenStore(private val dir: File) : TokenStore {
  override suspend fun get(accountId: Long): String? =
    withContext(Dispatchers.IO) {
      val file = file(accountId)
      if (!file.exists()) return@withContext null
      val bytes = file.readBytes()
      val iv = bytes.copyOfRange(0, IV_BYTES)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
      runCatching { cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString() }
        .getOrNull()
    }

  override suspend fun put(accountId: Long, token: String) =
    withContext(Dispatchers.IO) {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key())
      val encrypted = cipher.iv + cipher.doFinal(token.encodeToByteArray())
      dir.mkdirs()
      file(accountId).writeBytes(encrypted)
    }

  override suspend fun remove(accountId: Long) {
    withContext(Dispatchers.IO) { file(accountId).delete() }
  }

  private fun file(accountId: Long) = dir.resolve("account-$accountId")

  @Synchronized
  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (keyStore.getKey(ALIAS, null) as SecretKey?)?.let {
      return it
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
          ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build(),
    )
    return generator.generateKey()
  }

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "tongs-account-tokens"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
  }
}

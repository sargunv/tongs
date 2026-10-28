package dev.sargunv.tongs.core.data

import com.github.javakeyring.Keyring
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop tokens in the OS keychain: the Secret Service (GNOME Keyring, KWallet) on Linux, the
 * Keychain on macOS, and the Credential Manager on Windows, through java-keyring.
 *
 * Where there's no keychain, such as a Linux session without a Secret Service, tokens fall back to
 * a file only the user can read, in [fallbackDir]. That's no safer than `gh`'s own fallback, but no
 * worse either.
 */
class KeychainTokenStore(private val fallbackDir: File) : TokenStore {
  private val keyring: Keyring? =
    try {
      Keyring.create()
    } catch (e: Exception) {
      System.err.println("No OS keychain, so tokens go in a private file: $e")
      null
    }
  private val fileLock = Mutex()

  override suspend fun get(accountId: Long): String? =
    withContext(Dispatchers.IO) {
      keyring?.let { k -> runCatching { k.getPassword(SERVICE, key(accountId)) }.getOrNull() }
        ?: fileLock.withLock { readFile().getProperty(key(accountId)) }
    }

  override suspend fun put(accountId: Long, token: String) =
    withContext(Dispatchers.IO) {
      val stored =
        keyring?.let { k ->
          runCatching { k.setPassword(SERVICE, key(accountId), token) }.isSuccess
        } ?: false
      if (!stored) fileLock.withLock { writeFile { setProperty(key(accountId), token) } }
    }

  override suspend fun remove(accountId: Long) =
    withContext(Dispatchers.IO) {
      keyring?.let { k -> runCatching { k.deletePassword(SERVICE, key(accountId)) } }
      fileLock.withLock { writeFile { remove(key(accountId)) } }
    }

  private fun key(accountId: Long) = "account-$accountId"

  private val file
    get() = fallbackDir.resolve("tokens.properties")

  private fun readFile() = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

  private fun writeFile(edit: Properties.() -> Unit) {
    val properties = readFile().apply(edit)
    if (properties.isEmpty && !file.exists()) return
    fallbackDir.mkdirs()
    if (!file.exists()) {
      file.createNewFile()
      runCatching {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
      }
    }
    file.outputStream().use { properties.store(it, "Tongs account tokens") }
  }

  private companion object {
    const val SERVICE = "dev.sargunv.tongs"
  }
}

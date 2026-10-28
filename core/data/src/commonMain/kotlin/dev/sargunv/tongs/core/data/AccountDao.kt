package dev.sargunv.tongs.core.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import dev.sargunv.tongs.forge.api.Account
import dev.sargunv.tongs.forge.api.AccountId
import dev.sargunv.tongs.forge.api.AuthMethod
import dev.sargunv.tongs.forge.api.Capability
import dev.sargunv.tongs.forge.api.ForgeKind
import dev.sargunv.tongs.forge.api.Instance
import kotlinx.coroutines.flow.Flow

/** A signed-in account. Its token lives in the [TokenStore], not here. */
@Entity(
  tableName = "account",
  indices = [Index(value = ["forge", "webUrl", "userId"], unique = true)],
)
data class AccountEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  /** A [ForgeKind] name. */
  val forge: String,
  val webUrl: String,
  val apiUrl: String,
  /** The forge's ID for the user. */
  val userId: String,
  val login: String,
  val name: String?,
  val avatarUrl: String?,
  /** An [AuthMethod] name. */
  val authMethod: String,
  /** [Capability] IDs, comma-separated. */
  val capabilities: String,
  /** Requested scopes the token lacks, comma-separated. */
  val missingScopes: String,
) {
  fun toAccount() =
    Account(
      id = AccountId(id),
      instance = Instance(ForgeKind.valueOf(forge), webUrl, apiUrl),
      userId = userId,
      login = login,
      name = name,
      avatarUrl = avatarUrl,
      authMethod = AuthMethod.valueOf(authMethod),
      capabilities =
        capabilities
          .split(',')
          .mapNotNull { id -> Capability.entries.find { it.id == id } }
          .toSet(),
    )
}

@Dao
interface AccountDao {
  @Query("SELECT * FROM account ORDER BY id") fun observeAll(): Flow<List<AccountEntity>>

  @Query("SELECT * FROM account ORDER BY id") suspend fun all(): List<AccountEntity>

  @Query("SELECT * FROM account WHERE id = :id") suspend fun get(id: Long): AccountEntity?

  @Query("SELECT * FROM account WHERE forge = :forge AND webUrl = :webUrl AND userId = :userId")
  suspend fun find(forge: String, webUrl: String, userId: String): AccountEntity?

  /** Returns the row ID. */
  @Upsert suspend fun upsert(account: AccountEntity): Long

  @Query("DELETE FROM account WHERE id = :id") suspend fun delete(id: Long)
}

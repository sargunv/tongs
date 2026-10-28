package dev.sargunv.tongs.core.data

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The app's store and source of truth: screens observe it, and sync writes to it. SQLite is bundled
 * rather than the platform's, so both platforms run the same version.
 */
@Database(
  entities = [AccountEntity::class, InboxThreadEntity::class, InboxSyncEntity::class],
  version = 1,
)
@ConstructedBy(TongsDatabaseConstructor::class)
abstract class TongsDatabase : RoomDatabase() {
  abstract fun accounts(): AccountDao

  abstract fun inbox(): InboxDao
}

// Room generates the actual objects.
@Suppress("KotlinNoActualForExpect")
expect object TongsDatabaseConstructor : RoomDatabaseConstructor<TongsDatabase> {
  override fun initialize(): TongsDatabase
}

/** Opens the database at [path], creating it if needed. */
fun openDatabase(path: String): TongsDatabase =
  databaseBuilder(path)
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()

internal expect fun databaseBuilder(path: String): RoomDatabase.Builder<TongsDatabase>

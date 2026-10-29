package dev.sargunv.tongs.core.data

import androidx.room3.AutoMigration
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.DeleteColumn
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The app's store and source of truth: screens observe it, and sync writes to it. SQLite is bundled
 * rather than the platform's, so both platforms run the same version.
 */
@Database(
  entities =
    [
      AccountEntity::class,
      InboxThreadEntity::class,
      InboxSyncEntity::class,
      InboxViewEntity::class,
      ThreadEntity::class,
      ThreadItemEntity::class,
      ReviewThreadEntity::class,
      DraftEntity::class,
      // PR changes; see PullRequestChangesDao.kt.
      PullRequestOverviewEntity::class,
      ViewedFileEntity::class,
      DraftCommentEntity::class,
      DraftReviewEntity::class,
      ChangesThreadEntity::class,
      ChangesThreadCommentEntity::class,
    ],
  version = 5,
  autoMigrations =
    [
      AutoMigration(from = 1, to = 2),
      AutoMigration(from = 2, to = 3),
      AutoMigration(from = 3, to = 4),
      AutoMigration(from = 4, to = 5, spec = DropKept::class),
    ],
)
@ConstructedBy(TongsDatabaseConstructor::class)
abstract class TongsDatabase : RoomDatabase() {
  abstract fun accounts(): AccountDao

  abstract fun inbox(): InboxDao

  abstract fun inboxViews(): InboxViewDao

  abstract fun threads(): ThreadDao

  abstract fun drafts(): DraftDao

  abstract fun pullRequestChanges(): PullRequestChangesDao
}

/**
 * Version 5 keeps every thread until it's done here, so `kept` (triaged here) has nothing left to
 * say; `readElsewhere` is new and starts false, since threads that went read elsewhere were
 * dropped.
 */
@DeleteColumn(tableName = "inbox_thread", columnName = "kept") class DropKept : AutoMigrationSpec

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

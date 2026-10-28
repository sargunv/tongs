package dev.sargunv.tongs.core.data

import androidx.room3.AutoMigration
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
  version = 4,
  autoMigrations =
    [
      AutoMigration(from = 1, to = 2),
      AutoMigration(from = 2, to = 3),
      AutoMigration(from = 3, to = 4),
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

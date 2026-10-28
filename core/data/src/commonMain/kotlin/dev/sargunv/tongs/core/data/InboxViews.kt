package dev.sargunv.tongs.core.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

/**
 * A saved inbox view: a name for a filter in GitHub's vocabulary (see [InboxQuery]), and whether to
 * group it by repo. Local, since GitHub has no API for its saved filters. Views span every account.
 */
@Entity(tableName = "inbox_view")
data class InboxViewEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val query: String,
  val groupByRepo: Boolean,
  /** Order among the views, lowest first. */
  val position: Int,
)

@Dao
interface InboxViewDao {
  @Query("SELECT * FROM inbox_view ORDER BY position, id")
  fun observeAll(): Flow<List<InboxViewEntity>>

  @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM inbox_view") suspend fun nextPosition(): Int

  @Query("SELECT * FROM inbox_view WHERE id = :id") suspend fun get(id: Long): InboxViewEntity?

  @Upsert suspend fun upsert(view: InboxViewEntity): Long

  @Query("DELETE FROM inbox_view WHERE id = :id") suspend fun delete(id: Long)
}

/** Saved inbox views. */
@SingleIn(AppScope::class)
@Inject
class InboxViewRepository(db: TongsDatabase) {
  private val dao = db.inboxViews()

  val views: Flow<List<InboxViewEntity>> = dao.observeAll()

  /** Adds a view after the others, or updates it if [id] is an existing view's. Returns its ID. */
  suspend fun save(id: Long?, name: String, query: String, groupByRepo: Boolean): Long {
    val view =
      InboxViewEntity(
        id = id ?: 0,
        name = name.trim(),
        query = query.trim(),
        groupByRepo = groupByRepo,
        position = id?.let { dao.get(it)?.position } ?: dao.nextPosition(),
      )
    val inserted = dao.upsert(view)
    return if (id != null) id else inserted
  }

  suspend fun delete(id: Long) = dao.delete(id)
}

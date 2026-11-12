package app.her.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The outfit worn on a given day. Stays on the phone; drives "avoid repeats" and likes. */
@Entity(tableName = "outfit_records", primaryKeys = ["companionId", "date"])
data class OutfitRecordEntity(
    val companionId: String,
    val date: String,            // yyyy-MM-dd
    val itemIds: String,         // comma separated
    val summary: String,
    val source: String,          // auto | shuffle | manual | surprise
    val liked: Int? = null,      // 1 like, -1 dislike
    val saved: Boolean = false,
    val shuffles: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val ids: List<String> get() = itemIds.split(',').filter { it.isNotBlank() }
}

/** A messaging notification the user chose to surface. Content stays on the phone. */
@Entity(tableName = "notifications", indices = [Index("postedAt")])
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val packageName: String,
    val source: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,
    val fromPartner: Boolean,
    val read: Boolean = false,
)

@Dao
interface OutfitDao {
    @Query("SELECT * FROM outfit_records WHERE companionId = :companionId ORDER BY date DESC LIMIT :limit")
    fun observe(companionId: String, limit: Int = 60): Flow<List<OutfitRecordEntity>>

    @Query("SELECT * FROM outfit_records WHERE companionId = :companionId AND date = :date")
    suspend fun get(companionId: String, date: String): OutfitRecordEntity?

    @Upsert
    suspend fun upsert(record: OutfitRecordEntity)

    @Query("DELETE FROM outfit_records")
    suspend fun clear()
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY postedAt DESC LIMIT 200")
    fun observe(): Flow<List<NotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NotificationEntity)

    @Query("UPDATE notifications SET read = 1")
    suspend fun markAllRead()

    @Query("UPDATE notifications SET read = 1 WHERE `key` = :key")
    suspend fun markRead(key: String)

    @Query("DELETE FROM notifications WHERE postedAt < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM notifications")
    suspend fun clear()
}

@Database(entities = [OutfitRecordEntity::class, NotificationEntity::class], version = 1, exportSchema = true)
abstract class HerDatabase : RoomDatabase() {
    abstract fun outfits(): OutfitDao
    abstract fun notifications(): NotificationDao

    companion object {
        fun create(context: Context): HerDatabase =
            Room.databaseBuilder(context, HerDatabase::class.java, "her.db").build()
    }
}

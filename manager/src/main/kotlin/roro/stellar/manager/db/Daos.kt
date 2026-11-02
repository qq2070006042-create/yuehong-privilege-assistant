package roro.stellar.manager.db

import androidx.room.*

@Dao
interface CommandDao {
    @Query("SELECT * FROM commands")
    suspend fun getAll(): List<CommandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(commands: List<CommandEntity>)

    @Query("DELETE FROM commands")
    suspend fun deleteAll()

    @Query("UPDATE commands SET enabled = :enabled, executionCount = CASE WHEN :enabled = 1 THEN 0 ELSE executionCount END, successCount = CASE WHEN :enabled = 1 THEN 0 ELSE successCount END, failureCount = CASE WHEN :enabled = 1 THEN 0 ELSE failureCount END WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE commands SET successCount = successCount + :success, failureCount = failureCount + :failure WHERE id = :id")
    suspend fun recordExecution(id: String, success: Int, failure: Int)

    @Query("UPDATE commands SET executionCount = executionCount + 1, enabled = CASE WHEN executionCount + 1 >= maxExecutions THEN 0 ELSE enabled END WHERE id = :id AND mode = 'FOLLOW_SERVICE_ONCE' AND enabled = 1 AND executionCount < maxExecutions")
    suspend fun claimExecution(id: String): Int
}

@Dao
interface LogDao {
    @Query("SELECT line FROM logs ORDER BY id DESC")
    fun getAll(): List<String>

    @Query("SELECT line FROM logs ORDER BY id DESC LIMIT :limit OFFSET :offset")
    fun getPage(limit: Int, offset: Int): List<String>

    @Insert
    fun insert(entity: LogEntity)

    @Query("DELETE FROM logs")
    fun deleteAll()
}

@Dao
interface ConfigDao {
    @Query("SELECT value FROM config WHERE `key` = :key")
    fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun set(entity: ConfigEntity)

    @Query("SELECT * FROM config")
    fun getAll(): List<ConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setAll(entities: List<ConfigEntity>)
}

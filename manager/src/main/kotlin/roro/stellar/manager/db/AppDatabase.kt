package roro.stellar.manager.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CommandEntity::class, ConfigEntity::class, LogEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun commandDao(): CommandDao
    abstract fun configDao(): ConfigDao
    abstract fun logDao(): LogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            val deviceContext = context.applicationContext.createDeviceProtectedStorageContext()
            runCatching { deviceContext.moveDatabaseFrom(context.applicationContext, DATABASE_NAME) }
            instance ?: Room.databaseBuilder(deviceContext, AppDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE commands ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE commands ADD COLUMN maxExecutions INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE commands ADD COLUMN executionCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE commands ADD COLUMN successCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE commands ADD COLUMN failureCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE commands SET maxExecutions = 1 WHERE mode = 'FOLLOW_SERVICE_ONCE'")
            }
        }

        private const val DATABASE_NAME = "stellar.db"
    }
}

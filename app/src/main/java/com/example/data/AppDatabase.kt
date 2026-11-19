package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [OrderEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun orderDao(): OrderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_history_timestamp` ON `order_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_history_isAccepted` ON `order_history` (`isAccepted`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `order_history_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `fare` REAL NOT NULL,
                        `pickupDistanceKm` REAL,
                        `dropDistanceKm` REAL,
                        `pickupLocation` TEXT NOT NULL,
                        `dropLocation` TEXT NOT NULL,
                        `isAccepted` INTEGER NOT NULL,
                        `decisionReason` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `order_history_new` (
                        `id`, `timestamp`, `fare`, `pickupDistanceKm`, `dropDistanceKm`,
                        `pickupLocation`, `dropLocation`, `isAccepted`, `decisionReason`
                    )
                    SELECT
                        `id`, `timestamp`, `fare`, `pickupDistanceKm`, `dropDistanceKm`,
                        `pickupLocation`, `dropLocation`, `isAccepted`, `decisionReason`
                    FROM `order_history`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `order_history`")
                db.execSQL("ALTER TABLE `order_history_new` RENAME TO `order_history`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_history_timestamp` ON `order_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_history_isAccepted` ON `order_history` (`isAccepted`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "order_master_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

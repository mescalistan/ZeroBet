package com.zerobet.safeguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BlockedDomain::class, WhitelistedBank::class,
        SyncedDomain::class, AutoLearnedBlockedDomain::class, SyncStatus::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun domainDao(): DomainDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "safeguard.db"
                )
                    .addCallback(SeedCallback)
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }

        /**
         * Pre-popola la blacklist alla creazione del database. Si usa SQL diretto sul
         * SupportSQLiteDatabase perché in onCreate il DAO non è ancora utilizzabile;
         * l'inserimento avviene in modo sincrono dentro la transazione di creazione.
         */
        private object SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                insertSeed(db, DefaultData.seedEntries())
            }
        }

        private fun insertSeed(db: SupportSQLiteDatabase, entries: List<BlockedDomain>) {
            entries.forEach { entry ->
                db.execSQL(
                    "INSERT OR IGNORE INTO blocked_domains (pattern, category, isKeyword, userAdded) VALUES (?, ?, ?, 0)",
                    arrayOf<Any>(entry.pattern, entry.category, if (entry.isKeyword) 1 else 0)
                )
            }
        }

        /**
         * v2: tabelle per fonti esterne, domini auto-appresi e stato sync, più le nuove voci
         * predefinite (affiliazioni e resolver DoH). Whitelist e blacklist esistenti restano intatte.
         * Le CREATE devono coincidere esattamente con lo schema generato da Room per le entity.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `synced_domains` (`domain` TEXT NOT NULL, `source` TEXT NOT NULL, `batch` INTEGER NOT NULL, PRIMARY KEY(`domain`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_synced_domains_source` ON `synced_domains` (`source`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `auto_learned_blocked_domains` (`domain` TEXT NOT NULL, `origin` TEXT NOT NULL, `detail` TEXT NOT NULL, `learnedAt` INTEGER NOT NULL, PRIMARY KEY(`domain`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_status` (`source` TEXT NOT NULL, `lastSuccessAt` INTEGER NOT NULL, `domainCount` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`source`))")
                insertSeed(db, DefaultData.seedEntriesV2())
            }
        }
    }
}

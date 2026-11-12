package com.zerobet.safeguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BlockedDomain::class, WhitelistedBank::class],
    version = 1,
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
                DefaultData.seedEntries().forEach { entry ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO blocked_domains (pattern, category, isKeyword, userAdded) VALUES (?, ?, ?, 0)",
                        arrayOf<Any>(entry.pattern, entry.category, if (entry.isKeyword) 1 else 0)
                    )
                }
            }
        }
    }
}

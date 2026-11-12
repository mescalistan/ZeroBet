package com.zerobet.safeguard.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DomainDao {

    @Query("SELECT * FROM blocked_domains ORDER BY userAdded DESC, category, pattern")
    fun observeBlocked(): Flow<List<BlockedDomain>>

    @Query("SELECT * FROM whitelisted_banks ORDER BY name")
    fun observeWhitelist(): Flow<List<WhitelistedBank>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlocked(domain: BlockedDomain): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWhitelisted(bank: WhitelistedBank): Long

    @Delete
    suspend fun deleteBlocked(domain: BlockedDomain)

    @Delete
    suspend fun deleteWhitelisted(bank: WhitelistedBank)
}

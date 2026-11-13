package com.zerobet.safeguard.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Gravità di un evento del Registro Allarmi. */
object Severity {
    const val INFO = "INFO"
    const val WARNING = "WARNING"
    const val CRITICAL = "CRITICAL"
}

/** Tipo di evento: decide in quale filtro della dashboard compare. */
object EventType {
    const val TAMPER = "TAMPER"         // Guardiano: Impostazioni protette, disinstallazione
    const val SIM = "SIM"               // SIM rimossa, inserita, operatore cambiato
    const val PIN = "PIN"               // PIN errato
    const val POWER = "POWER"           // spegnimento e riavvio
    const val INTEGRITY = "INTEGRITY"   // servizio obbligatorio trovato spento
    const val SEARCH = "SEARCH"         // ricerca a rischio bloccata
    const val DOMAIN = "DOMAIN"         // dominio bloccato dal filtro DNS
    const val CONTENT = "CONTENT"       // pagina, video, post o pubblicità bloccati per i contenuti
    const val APP = "APP"               // app dedicata (trading, scommesse, prestiti) chiusa all'apertura

    /** Filtro "Tentativi di manomissione / SIM". */
    val TAMPER_GROUP = listOf(TAMPER, SIM, PIN, POWER, INTEGRITY, APP)
    val SEARCH_GROUP = listOf(SEARCH, CONTENT)
    val DOMAIN_GROUP = listOf(DOMAIN)
}

/**
 * Riga del "Diario di sicurezza". [count] e [lastAt] servono a raggruppare gli eventi ripetuti:
 * 50 richieste DNS allo stesso dominio in un minuto diventano una riga sola con contatore x50.
 */
@Entity(tableName = "security_event_log", indices = [Index("timestamp"), Index("type")])
data class SecurityEventLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val severity: String,
    val type: String,
    val title: String,
    val detail: String,
    val count: Int = 1,
    val lastAt: Long = timestamp
)

@Dao
interface SecurityEventDao {

    @Insert
    fun insert(event: SecurityEventLog): Long

    @Query("UPDATE security_event_log SET count = :count, lastAt = :lastAt WHERE id = :id")
    fun updateCount(id: Long, count: Int, lastAt: Long)

    @Query("SELECT * FROM security_event_log ORDER BY lastAt DESC, id DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<SecurityEventLog>>

    @Query("SELECT * FROM security_event_log WHERE type IN (:types) ORDER BY lastAt DESC, id DESC LIMIT :limit")
    fun observeByTypes(types: List<String>, limit: Int): Flow<List<SecurityEventLog>>

    @Query("SELECT COUNT(*) FROM security_event_log")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM security_event_log WHERE severity = 'CRITICAL' AND timestamp > :since")
    fun observeCriticalSince(since: Long): Flow<Int>

    @Query("DELETE FROM security_event_log")
    fun clear()

    /** Tiene il registro entro [keep] righe: le più vecchie vengono eliminate. */
    @Query("DELETE FROM security_event_log WHERE id <= (SELECT MAX(id) FROM security_event_log) - :keep")
    fun trim(keep: Int)
}

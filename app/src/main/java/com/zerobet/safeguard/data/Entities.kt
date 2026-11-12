package com.zerobet.safeguard.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Categorie della blacklist. Salvate come stringa nella colonna `category`. */
object Category {
    const val GAMBLING = "GAMBLING"   // Scommesse / Casinò
    const val TRADING = "TRADING"     // Trading / Crypto
    const val LOANS = "LOANS"         // Finanziarie / Prestiti
    const val BANK = "BANK"           // Banche (bloccate se non in whitelist)
    const val ADS = "ADS"             // Reti pubblicitarie e affiliazioni di settore
    const val DOH = "DOH"             // Resolver DNS-over-HTTPS (anti-bypass)
    const val CUSTOM = "CUSTOM"       // Aggiunte dal tutore

    fun label(category: String): String = when (category) {
        GAMBLING -> "Scommesse e casinò"
        TRADING -> "Trading e crypto"
        LOANS -> "Prestiti e finanziarie"
        BANK -> "Banche non autorizzate"
        ADS -> "Pubblicità e affiliazioni"
        DOH -> "Resolver DoH (anti-bypass)"
        else -> "Personalizzati"
    }
}

/**
 * Voce di blacklist.
 * - isKeyword = false: `pattern` è un dominio; blocca il dominio stesso e tutti i sottodomini.
 * - isKeyword = true:  `pattern` è una parola chiave; blocca ogni dominio che la contiene.
 */
@Entity(
    tableName = "blocked_domains",
    indices = [Index(value = ["pattern", "isKeyword"], unique = true)]
)
data class BlockedDomain(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val category: String,
    val isKeyword: Boolean = false,
    val userAdded: Boolean = false
)

/** Dominio bancario autorizzato: ha sempre la precedenza sulla blacklist (sottodomini inclusi). */
@Entity(
    tableName = "whitelisted_banks",
    indices = [Index(value = ["domain"], unique = true)]
)
data class WhitelistedBank(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val domain: String
)

/**
 * Dominio proveniente da una fonte esterna (blocklist pubbliche, ADM, CONSOB, crt.sh).
 * Tabella potenzialmente molto grande: non viene mai mostrata per intero nella UI, ma
 * caricata in un HashSet in memoria dal filtro DNS.
 * `batch` è il timestamp della sincronizzazione che ha scritto la riga: a download riuscito
 * le righe della stessa fonte con batch più vecchio vengono eliminate.
 */
@Entity(tableName = "synced_domains", indices = [Index(value = ["source"])])
data class SyncedDomain(
    @PrimaryKey val domain: String,
    val source: String,
    val batch: Long
)

/**
 * Dominio aggiunto automaticamente dall'app sul dispositivo:
 *  - origin = HEURISTIC: punteggio di rischio del nome a dominio sopra soglia (filtro DNS)
 *  - origin = SCREEN:    contenuti di gioco/trading/prestiti rilevati a schermo nel browser
 */
@Entity(tableName = "auto_learned_blocked_domains")
data class AutoLearnedBlockedDomain(
    @PrimaryKey val domain: String,
    val origin: String,
    val detail: String,
    val learnedAt: Long
) {
    companion object {
        const val ORIGIN_HEURISTIC = "HEURISTIC"
        const val ORIGIN_SCREEN = "SCREEN"
    }
}

/** Esito dell'ultima sincronizzazione di una fonte esterna. */
@Entity(tableName = "sync_status")
data class SyncStatus(
    @PrimaryKey val source: String,
    val lastSuccessAt: Long,
    val domainCount: Int,
    val lastError: String?
)

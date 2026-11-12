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
    const val CUSTOM = "CUSTOM"       // Aggiunte dal tutore

    fun label(category: String): String = when (category) {
        GAMBLING -> "Scommesse e casinò"
        TRADING -> "Trading e crypto"
        LOANS -> "Prestiti e finanziarie"
        BANK -> "Banche non autorizzate"
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

package com.zerobet.safeguard.sync

import java.io.BufferedReader
import java.io.PushbackReader
import java.io.Reader

/** Radice lessicale da cercare nei log di Certificate Transparency. */
class CtKeyword(
    val term: String,
    /**
     * true per i marchi il cui nome compare anche dentro parole comuni (es. "agos" in "lagos",
     * "snai" in "snail", "compass" in "compassion"): in quel caso non basta che il nome contenga
     * il termine, deve comparire come parola a sé (vedi [CtLogDiscovery.candidates]).
     */
    val strict: Boolean = false
)

/**
 * Scoperta proattiva di nuovi domini tramite Certificate Transparency.
 *
 * Ogni certificato TLS emesso finisce in log pubblici: interrogando crt.sh per radici lessicali
 * ad alto rischio si trovano i domini "mirror" appena registrati, prima che finiscano in
 * qualunque blocklist. Questo oggetto contiene solo la logica pura (scelta delle parole,
 * parsing in streaming del JSON, filtro dei candidati); download e scrittura su DB sono nel
 * BlacklistSyncWorker.
 */
object CtLogDiscovery {

    val KEYWORDS: List<CtKeyword> = listOf(
        // --- Gambling ---
        CtKeyword("scommess"), CtKeyword("casin", strict = true), CtKeyword("slotmachine"),
        CtKeyword("roulette"), CtKeyword("poker"), CtKeyword("betting"), CtKeyword("bookmaker"),
        CtKeyword("jackpot"), CtKeyword("freespin"), CtKeyword("snai", strict = true),
        CtKeyword("sisal", strict = true), CtKeyword("goldbet"), CtKeyword("eurobet"),
        CtKeyword("planetwin"), CtKeyword("betflag"),
        // --- Trading / investimenti ---
        CtKeyword("forex"), CtKeyword("cryptotrad"), CtKeyword("binaryoption"),
        CtKeyword("metatrader"), CtKeyword("copytrading"), CtKeyword("tradingonline"),
        CtKeyword("investiment"), CtKeyword("levafinanz"), CtKeyword("cfdtrading"),
        CtKeyword("etoro", strict = true), CtKeyword("plus500"), CtKeyword("pepperstone"),
        CtKeyword("scalablecapital"), CtKeyword("traderepublic"), CtKeyword("degiro", strict = true),
        CtKeyword("directa", strict = true),
        // --- Finanziamenti / prestiti ---
        CtKeyword("prestit"), CtKeyword("finanziament"), CtKeyword("cessionedelquinto"),
        CtKeyword("piccoloprestito"), CtKeyword("liquidita"), CtKeyword("consolidamentodebiti"),
        CtKeyword("findomestic"), CtKeyword("compass", strict = true), CtKeyword("agos", strict = true),
        CtKeyword("cofidis"), CtKeyword("pitagora", strict = true), CtKeyword("santanderconsumer"),
        CtKeyword("fiditalia"), CtKeyword("youllis"),
    )

    /** Quante parole interrogare per esecuzione: crt.sh è lento e limita le richieste. */
    const val KEYWORDS_PER_RUN = 6

    /** Si considerano solo i certificati emessi negli ultimi N giorni ("registrati di recente"). */
    const val RECENT_DAYS = 45L

    /**
     * Parole da interrogare oggi: una finestra che scorre sull'elenco, così in circa
     * una settimana vengono coperte tutte senza sovraccaricare crt.sh.
     */
    fun keywordsFor(epochDay: Long, perRun: Int = KEYWORDS_PER_RUN): List<CtKeyword> {
        val start = Math.floorMod(epochDay * perRun, KEYWORDS.size.toLong()).toInt()
        return List(perRun) { KEYWORDS[(start + it) % KEYWORDS.size] }
    }

    fun queryUrl(keyword: CtKeyword): String =
        "https://crt.sh/?q=%25${keyword.term}%25&output=json&exclude=expired"

    private val HOST = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{1,23}$")

    /** Affissi ammessi attorno a un marchio "strict": snaibet, agos-prestiti, login-etoro... */
    private const val AFFIX = "bet|casino|slot|poker|sport|live|online|italia|login|app|bonus|" +
        "prestiti|prestito|finanziamenti|trading|trade|invest|broker|club|vip|win|play|games?"

    /**
     * Estrae i domini candidati dal campo `name_value` di un certificato (uno o più nomi separati
     * da newline, eventualmente wildcard "*.dominio").
     *
     * Regole anti falsi positivi:
     *  - il termine deve stare nel nome vero e proprio, non nel TLD;
     *  - per i marchi "strict" l'intero nome di secondo livello (la label subito prima del TLD)
     *    deve essere il marchio, al più con cifre o un affisso tipico (bet, casino, prestiti...):
     *    "agos-prestiti.com" e "snai365.net" sì, "lagos.com", "snail.io" e "compass-group.com" no.
     */
    fun candidates(nameValue: String, keyword: CtKeyword): List<String> {
        val strictRegex = if (keyword.strict) {
            Regex("^(($AFFIX)-?)?${Regex.escape(keyword.term)}[o0i]?\\d{0,4}(-?($AFFIX))?$")
        } else null

        val out = ArrayList<String>(2)
        for (rawName in nameValue.split('\n')) {
            val host = rawName.trim().lowercase().removePrefix("*.").removePrefix("www.")
            if (host.length > 100 || !HOST.matches(host)) continue
            val body = host.substringBeforeLast('.')
            val hit = if (strictRegex != null) {
                strictRegex.matches(body.substringAfterLast('.'))
            } else {
                body.contains(keyword.term)
            }
            if (hit && host !in out) out.add(host)
        }
        return out
    }

    /**
     * Parser JSON in streaming per la risposta di crt.sh: un array di oggetti "piatti" che può
     * pesare decine di MB. Non costruisce mai l'albero in memoria: scorre i caratteri e, per ogni
     * oggetto, consegna a [onEntry] i soli campi `name_value` e `not_before`.
     *
     * @return numero di certificati letti (si ferma a [maxEntries]).
     */
    fun parseEntries(
        input: Reader,
        maxEntries: Int = 50_000,
        onEntry: (nameValue: String, notBefore: String) -> Unit
    ): Int {
        val reader = PushbackReader(if (input is BufferedReader) input else BufferedReader(input), 1)
        var nameValue: String? = null
        var notBefore = ""
        var count = 0
        while (true) {
            val c = reader.read()
            if (c < 0) break
            when (c.toChar()) {
                '"' -> {
                    // Una stringa seguita da ':' è una chiave; ne leggiamo il valore se è una stringa.
                    val key = readString(reader)
                    val afterKey = readSkippingWhitespace(reader)
                    if (afterKey == ':'.code) {
                        val valueStart = readSkippingWhitespace(reader)
                        if (valueStart == '"'.code) {
                            val value = readString(reader)
                            when (key) {
                                "name_value" -> nameValue = value
                                "not_before" -> notBefore = value
                            }
                        } else if (valueStart >= 0) {
                            reader.unread(valueStart)   // numero/null/oggetto: non ci interessa
                        }
                    } else if (afterKey >= 0) {
                        reader.unread(afterKey)
                    }
                }
                '}' -> {
                    // Fine di un certificato.
                    val name = nameValue
                    if (name != null) {
                        onEntry(name, notBefore)
                        count++
                        if (count >= maxEntries) return count
                    }
                    nameValue = null
                    notBefore = ""
                }
            }
        }
        return count
    }

    /** Legge una stringa JSON (il '"' di apertura è già stato consumato), gestendo gli escape. */
    private fun readString(reader: Reader): String {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0 || c == '"'.code) break
            if (c == '\\'.code) {
                when (val escaped = reader.read()) {
                    'n'.code -> sb.append('\n')
                    't'.code -> sb.append('\t')
                    'r'.code -> sb.append('\r')
                    'u'.code -> {
                        val hex = CharArray(4)
                        if (reader.read(hex, 0, 4) == 4) {
                            String(hex).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        }
                    }
                    -1 -> return sb.toString()
                    else -> sb.append(escaped.toChar())
                }
            } else {
                sb.append(c.toChar())
            }
        }
        return sb.toString()
    }

    private fun readSkippingWhitespace(reader: Reader): Int {
        while (true) {
            val c = reader.read()
            if (c < 0 || !c.toChar().isWhitespace()) return c
        }
    }
}

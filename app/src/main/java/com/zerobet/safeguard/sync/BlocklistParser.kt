package com.zerobet.safeguard.sync

/**
 * Parser delle blocklist esterne. Lavora una riga alla volta, così il chiamante può leggere
 * il download in streaming senza mai tenere in memoria l'intero file (alcune liste superano
 * le 300.000 righe). Kotlin puro: nessuna dipendenza Android, coperto da unit test.
 */
object BlocklistParser {

    /** Nome host valido: label alfanumeriche (anche "_" e "-") e un TLD che inizia con una lettera. */
    private val HOST = Regex("^([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\\.)+[a-z][a-z0-9-]{1,23}$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val WHITESPACE = Regex("\\s+")

    /** Nomi presenti nei file hosts che non sono domini da bloccare. */
    private val LOCAL_NAMES = hashSetOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters",
        "ip6-allhosts", "0.0.0.0",
    )

    /** URL o host preceduto da "www." dentro testo libero / HTML. */
    private val LINK = Regex("(?:https?://|\\bwww\\.)([a-z0-9][a-z0-9._-]*\\.[a-z][a-z0-9-]{1,23})", RegexOption.IGNORE_CASE)

    /** "TLD" che in realtà sono estensioni di file comparse in un link. */
    private val FILE_EXTENSIONS = hashSetOf(
        "html", "htm", "php", "asp", "aspx", "jsp", "css", "js", "json", "xml", "pdf", "txt",
        "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "zip", "doc", "docx", "xls", "xlsx",
    )

    /**
     * Estrae il dominio da una riga di blocklist, oppure null se la riga non ne contiene.
     * Formati gestiti:
     *   - hosts:    "0.0.0.0 esempio.com"  /  "127.0.0.1 esempio.com   # commento"
     *   - domains:  "esempio.com"
     *   - adblock:  "||esempio.com^"
     *   - commenti  "# ..." e "! ...", intestazioni "[Adblock Plus]", righe vuote
     * Il prefisso "www." viene rimosso: il filtro copre comunque tutti i sottodomini.
     */
    fun parseLine(rawLine: String): String? {
        var line = rawLine.trim()
        if (line.isEmpty()) return null
        when (line[0]) {
            '#', '!', '[' -> return null
        }
        val hash = line.indexOf('#')
        if (hash >= 0) line = line.substring(0, hash).trim()
        if (line.isEmpty()) return null

        if (line.startsWith("||")) line = line.substring(2).substringBefore('^').substringBefore('$')

        val tokens = line.split(WHITESPACE)
        // Formato hosts: il primo campo è l'indirizzo a cui "puntare" il dominio, il secondo il dominio.
        val candidate = if (tokens.size >= 2 && isAddress(tokens[0])) tokens[1] else tokens[0]
        return cleanHost(candidate)
    }

    /**
     * Estrae tutti i domini citati come link ("https://sito.com", "www.sito.com") in un testo
     * o in una riga HTML. Usato per le pagine di ADM e CONSOB, che non sono liste "pulite".
     */
    fun extractDomains(text: String): List<String> {
        if (text.indexOf('.') < 0) return emptyList()
        val out = ArrayList<String>(2)
        for (match in LINK.findAll(text)) {
            val host = cleanHost(match.groupValues[1]) ?: continue
            if (host.substringAfterLast('.') in FILE_EXTENSIONS) continue
            out.add(host)
        }
        return out
    }

    private fun isAddress(token: String): Boolean =
        IPV4.matches(token) || token == "::1" || token == "::" || token.startsWith("fe80:") || token.startsWith("ff0")

    private fun cleanHost(raw: String): String? {
        val host = raw.lowercase().trim().trimEnd('.', ',', ';').removePrefix("www.")
        if (host.length < 4 || host.length > 253) return null
        if (host in LOCAL_NAMES || IPV4.matches(host)) return null
        return host.takeIf { HOST.matches(it) }
    }
}

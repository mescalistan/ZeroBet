package com.zerobet.safeguard.guard

enum class ContentCategory(val label: String) {
    GAMBLING("scommesse e casinò"),
    TRADING("trading e investimenti"),
    LOANS("prestiti e finanziamenti"),
    /** App di una banca non autorizzata dal tutore (solo per il blocco delle app). */
    BANK("banca non autorizzata")
}

class ContentHit(val category: ContentCategory, val score: Int, val phrases: List<String>)

/**
 * Riconosce nel testo visibile a schermo le pagine (o i banner) che invitano a scommettere,
 * fare trading o chiedere un prestito. Kotlin puro, coperto da unit test.
 *
 * Per limitare i falsi positivi non basta una parola: servono COMBINAZIONI. Ogni espressione
 * ha un peso (3 = invito esplicito all'azione, 2 = termine tecnico, 1 = parola generica) e una
 * categoria scatta solo con almeno [MIN_DISTINCT] espressioni diverse e punteggio >= [THRESHOLD].
 * Esempio: "TAN" e "TAEG" in un articolo non bastano (3 punti); "Richiedi prestito" + "TAEG" sì.
 */
object ContentClassifier {

    const val THRESHOLD = 5
    const val MIN_DISTINCT = 2

    /** [prefix] = true: l'espressione è una radice e può proseguire (es. "scommess" -> scommesse, scommessa). */
    private class Phrase(val text: String, val weight: Int, val prefix: Boolean = false)

    private val RULES: Map<ContentCategory, List<Phrase>> = mapOf(
        ContentCategory.GAMBLING to listOf(
            Phrase("gioca ora", 3), Phrase("gioca subito", 3), Phrase("scommetti ora", 3),
            Phrase("bonus benvenuto", 3), Phrase("bonus di benvenuto", 3), Phrase("bonus senza deposito", 3),
            Phrase("giri gratis", 3), Phrase("free spin", 3, prefix = true), Phrase("deposita ora", 3),
            Phrase("piazza la scommessa", 3), Phrase("bet now", 3), Phrase("welcome bonus", 3),
            Phrase("schedina", 2), Phrase("puntata", 2), Phrase("scommess", 2, prefix = true),
            Phrase("casino", 2), Phrase("casinò", 2), Phrase("jackpot", 2), Phrase("slot machine", 2),
            Phrase("quota", 1), Phrase("quote", 1), Phrase("slot", 1), Phrase("vincita", 1),
        ),
        ContentCategory.TRADING to listOf(
            Phrase("leva finanziaria", 3), Phrase("apri conto demo", 3), Phrase("apri un conto demo", 3),
            Phrase("compra azioni", 3), Phrase("compra crypto", 3), Phrase("compra bitcoin", 3),
            Phrase("acquista azioni", 3), Phrase("acquista crypto", 3), Phrase("inizia a fare trading", 3),
            Phrase("fai trading", 3), Phrase("copy trading", 3), Phrase("investi ora", 3),
            Phrase("conto demo", 2), Phrase("trading online", 2), Phrase("cfd", 2), Phrase("forex", 2),
            Phrase("criptovalut", 2, prefix = true),
            Phrase("spread", 1), Phrase("trading", 1), Phrase("broker", 1),
        ),
        ContentCategory.LOANS to listOf(
            Phrase("richiedi prestito", 3), Phrase("richiedi il prestito", 3), Phrase("richiedi un prestito", 3),
            Phrase("richiedi il tuo prestito", 3), Phrase("richiedi subito", 3), Phrase("richiedi ora", 3),
            Phrase("calcola rata", 3), Phrase("calcola la rata", 3), Phrase("calcola la tua rata", 3),
            Phrase("cessione del quinto", 3), Phrase("preventivo prestito", 3), Phrase("prestito online", 3),
            Phrase("taeg", 2), Phrase("prestito personale", 2), Phrase("finanziament", 2, prefix = true),
            Phrase("tan", 1), Phrase("prestit", 1, prefix = true), Phrase("rata", 1),
        ),
    )

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Categoria con il punteggio più alto fra quelle sopra soglia, o null se il testo è innocuo. */
    fun classify(rawText: CharSequence): ContentHit? {
        if (rawText.length < 12) return null
        // Normalizzazione: minuscolo e separatori ridotti a uno spazio, con spazi ai bordi, così
        // " tan " corrisponde solo alla parola intera e non a "tanto" o "distanza".
        val text = " " + NON_WORD.replace(rawText.toString().lowercase(), " ").trim() + " "

        var best: ContentHit? = null
        for ((category, phrases) in RULES) {
            var score = 0
            val found = ArrayList<String>(4)
            for (phrase in phrases) {
                val needle = if (phrase.prefix) " ${phrase.text}" else " ${phrase.text} "
                if (text.contains(needle)) {
                    score += phrase.weight
                    found.add(phrase.text)
                }
            }
            if (score >= THRESHOLD && found.size >= MIN_DISTINCT && score > (best?.score ?: 0)) {
                best = ContentHit(category, score, found)
            }
        }
        return best
    }
}

package com.nutrition.tracker.util

import java.text.Normalizer

private val cyrillicToLatin = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
    'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
    'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
    'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
    'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
    'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
    'э' to "e", 'ю' to "yu", 'я' to "ya"
)

fun transliterateToLatin(text: String): String {
    return text.lowercase().map { ch ->
        cyrillicToLatin[ch] ?: ch.toString()
    }.joinToString("")
}

// Multi-char Latin expansions that Unicode NFD does NOT split (ß has no decomposition;
// æ/œ/ø are single letters). Handled explicitly so German/French/Nordic input folds too.
private val latinLigatures = mapOf(
    'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'đ' to "d", 'ð' to "d",
    'þ' to "th", 'ł' to "l", 'ı' to "i",
)

/**
 * Folds a string to a diacritic-free, lowercase ASCII-ish form so search works across ALL
 * Latin-script languages the app supports (de/es/fr/it/pt/en), the same way transliteration
 * makes it work for Cyrillic (ru/uk). Examples: "Käse" → "kase", "jamón" → "jamon",
 * "Gnocchi à la crème" → "gnocchi a la creme", "Straße" → "strasse".
 *
 * Steps: lowercase → expand ligatures (ß→ss, æ→ae…) → Unicode NFD (separates base letter
 * from combining accent) → drop combining marks (U+0300–U+036F).
 */
fun foldDiacritics(text: String): String {
    val expanded = buildString {
        for (ch in text.lowercase()) append(latinLigatures[ch] ?: ch.toString())
    }
    val decomposed = Normalizer.normalize(expanded, Normalizer.Form.NFD)
    return decomposed.replace(Regex("\\p{M}+"), "")
}

/**
 * Ranks saved-food suggestions for the given query so that the best matches survive the
 * top-N cap. Plain substring matching + take(5) buried short names like "помидор"/"огурец"
 * behind long dish names that merely contain the word ("Греческий салат с помидорами…"),
 * so a whole-word product only appeared after typing almost all of it.
 *
 * Matching is script-agnostic: each field and the query are compared in three normalized
 * forms so ALL supported languages match as forgivingly as Russian does —
 *   1) raw lowercase (exact input, any script),
 *   2) Cyrillic→Latin transliteration (ru/uk typed in Latin, e.g. "ogurec"),
 *   3) diacritic-folded ASCII (de/es/fr/it/pt, e.g. "kase" finds "Käse").
 *
 * Scoring (lower = better), taking the best score across all forms and both keys:
 *   0  exact match
 *   1  key starts with the query
 *   2  a WORD inside the key starts with the query (word-boundary prefix)
 *   3  substring match anywhere
 * Ties break by shorter key (a bare "огурец" outranks a long salad name), then alphabetically.
 * Non-matches are dropped. Returns up to [limit] entries.
 */
fun <T> rankFoodSuggestions(
    query: String,
    items: List<T>,
    keyOriginal: (T) -> String,
    keyEn: (T) -> String,
    limit: Int = 5,
): List<T> {
    val q = query.lowercase().trim()
    if (q.isEmpty()) return emptyList()
    // Three normalized forms of the query; blanks/duplicates removed.
    val needles = listOf(q, transliterateToLatin(q), foldDiacritics(q))
        .filter { it.isNotEmpty() }.distinct()

    fun scoreField(field: String): Int {
        // Compare the query forms against the matching normalized forms of the field.
        val forms = listOf(field.lowercase(), transliterateToLatin(field), foldDiacritics(field))
        var best = Int.MAX_VALUE
        for (f in forms.distinct()) {
            for (needle in needles) {
                if (f == needle) return 0
                if (f.startsWith(needle)) { best = minOf(best, 1); continue }
                if (f.split(' ', ',', '(', ')', '"', '/', '-').any { it.startsWith(needle) }) {
                    best = minOf(best, 2); continue
                }
                if (f.contains(needle)) best = minOf(best, 3)
            }
        }
        return best
    }

    return items
        .mapNotNull { item ->
            val score = minOf(scoreField(keyOriginal(item)), scoreField(keyEn(item)))
            if (score == Int.MAX_VALUE) null else Triple(item, score, keyOriginal(item).length)
        }
        .sortedWith(compareBy({ it.second }, { it.third }, { keyOriginal(it.first).lowercase() }))
        .take(limit)
        .map { it.first }
}

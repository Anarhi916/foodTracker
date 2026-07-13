package com.nutrition.tracker.util

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Internationalization helpers, mirroring the iOS app.
 *
 * Key principle: values STORED in the DB or SENT to the AI must be
 * language-neutral canonical tokens; only the displayed text is localized.
 */
enum class Gender(val storedValue: String) {
    MALE("male"),
    FEMALE("female");

    /** English word passed to the AI norms prompt. */
    val promptValue: String get() = storedValue

    companion object {
        /** Parse a stored profile gender, accepting new canonical tokens and
         *  legacy Russian values so existing profiles keep working. */
        fun fromStored(value: String): Gender = when (value.lowercase().trim()) {
            "female", "женский", "жіноча", "ж", "f" -> FEMALE
            else -> MALE
        }
    }
}

object AppLocale {
    /** Two-letter language code of the app's current locale. */
    val languageCode: String get() = Locale.getDefault().language.ifEmpty { "en" }

    /** English name of the current language for embedding in AI prompts. */
    val languageEnglishName: String
        get() = mapOf(
            "ru" to "Russian", "uk" to "Ukrainian", "en" to "English",
            "de" to "German", "es" to "Spanish", "fr" to "French",
            "it" to "Italian", "pt" to "Portuguese"
        )[languageCode] ?: "English"
}

/**
 * In-app language switching. Uses AppCompat's per-app locale API, which
 * backports to API < 33 and (with the AppLocalesMetadataHolderService in the
 * manifest) auto-persists the choice. An empty tag = follow the system language.
 */
object LanguageOption {
    /** Supported languages as (BCP-47 tag, native endonym). Empty tag = system. */
    val supported: List<Pair<String, String>> = listOf(
        "" to "", // system default — label resolved in UI
        "ru" to "Русский",
        "uk" to "Українська",
        "en" to "English",
        "de" to "Deutsch",
        "es" to "Español",
        "fr" to "Français",
        "it" to "Italiano",
        "pt" to "Português"
    )

    /** Current explicitly-selected language tag, or "" if following the system. */
    fun currentTag(): String {
        val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "" else (locales[0]?.language ?: "")
    }

    /** Apply a language. Empty tag resets to the system language. */
    fun setLanguage(tag: String) {
        val list = if (tag.isEmpty()) {
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        } else {
            androidx.core.os.LocaleListCompat.forLanguageTags(tag)
        }
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(list)
    }
}

/** Measurement system for food weight. Weight is always STORED in grams. */
enum class UnitSystem { METRIC, IMPERIAL;
    companion object {
        private const val PREF = "settings"
        private const val KEY = "unit_system"

        fun current(context: Context): UnitSystem {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            prefs.getString(KEY, null)?.let { return valueOf(it) }
            val region = Locale.getDefault().country
            return if (region in setOf("US", "LR", "MM")) IMPERIAL else METRIC
        }

        fun set(context: Context, system: UnitSystem) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, system.name).apply()
        }
    }
}

/** Conversion helpers for BODY weight (kg) and height (cm), stored in metric.
 *  Imperial UI collects pounds and feet+inches instead. */
object BodyUnits {
    const val KG_PER_POUND = 0.45359237
    const val CM_PER_INCH = 2.54
    const val CM_PER_FOOT = 30.48

    fun kgToPounds(kg: Double): Double = kg / KG_PER_POUND
    fun poundsToKg(lb: Double): Double = lb * KG_PER_POUND

    /** cm → (feet, inches), rounding inches and carrying to feet at 12. */
    fun cmToFeetInches(cm: Double): Pair<Int, Int> {
        val totalInches = cm / CM_PER_INCH
        var feet = (totalInches / 12.0).toInt()
        var inches = Math.round(totalInches - feet * 12.0).toInt()
        if (inches == 12) { feet += 1; inches = 0 }
        return feet to inches
    }

    fun feetInchesToCm(feet: Double, inches: Double): Double =
        feet * CM_PER_FOOT + inches * CM_PER_INCH
}

/** Weight display/conversion honoring [UnitSystem]. Grams are canonical. */
object WeightFormat {
    const val GRAMS_PER_OUNCE = 28.3495
    const val GRAMS_PER_POUND = 453.592

    /** Compact weight for tables/rows, e.g. "150 g" or "5.3 oz". */
    fun short(context: Context, grams: Double): String {
        return when (UnitSystem.current(context)) {
            UnitSystem.METRIC ->
                "${grams.toInt()}${context.getString(com.nutrition.tracker.R.string.gram_short)}"
            UnitSystem.IMPERIAL -> {
                val oz = grams / GRAMS_PER_OUNCE
                val num = String.format(Locale.getDefault(), "%.1f", oz)
                "$num ${context.getString(com.nutrition.tracker.R.string.oz)}"
            }
        }
    }

    fun toGrams(context: Context, displayValue: Double): Double =
        when (UnitSystem.current(context)) {
            UnitSystem.METRIC -> displayValue
            UnitSystem.IMPERIAL -> displayValue * GRAMS_PER_OUNCE
        }

    fun fromGrams(context: Context, grams: Double): Double =
        when (UnitSystem.current(context)) {
            UnitSystem.METRIC -> grams
            UnitSystem.IMPERIAL -> grams / GRAMS_PER_OUNCE
        }
}

/**
 * Centralized weight parsing out of free-text food input, across supported
 * languages and unit systems. Replaces the duplicated regexes.
 */
object WeightParser {
    private val regex = Regex(
        """(\d+(?:[.,]\d+)?)\s*(килограмм|kilograms?|kilogramm|kilogramos|грамм|gramm|gramos|grammi|gramas?|унци[яйіи]|ounces?|фунт[аовыи]*|pounds?|кг|kg|гр|г|g|ml|мл|oz|lb)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun toGrams(value: Double, unit: String): Double = when (unit.lowercase()) {
        "кг", "kg", "килограмм", "kilogram", "kilograms", "kilogramm", "kilogramos" -> value * 1000
        "oz", "унция", "унции", "унцій", "ounce", "ounces" -> value * WeightFormat.GRAMS_PER_OUNCE
        "lb", "фунт", "фунта", "фунтов", "фунты", "pound", "pounds" -> value * WeightFormat.GRAMS_PER_POUND
        else -> value // grams / ml in any language spelling
    }

    /** Extract (nameWithoutWeight, grams) from a single food item string. */
    fun parse(item: String): Pair<String, Double> {
        val match = regex.find(item) ?: return item to 0.0
        val value = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
        val grams = toGrams(value, match.groupValues[2])
        val name = item.removeRange(match.range).trim()
        return name to grams
    }
}

package com.nutrition.tracker.util

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.gson.Gson
import com.nutrition.tracker.R
import com.nutrition.tracker.data.model.NutrientData

/**
 * Cross-platform food sharing via deep links.
 *
 * Two URL formats are supported:
 * 1. Custom scheme (direct):  nutritrack://food?v=1&d=<base64url(json)>
 * 2. HTTPS (clickable everywhere): https://anarhi916.github.io/nutritrack/?v=1&d=<base64url(json)>
 *
 * We SEND the HTTPS form so it clicks in messengers (Viber/Telegram/WhatsApp),
 * but ACCEPT both forms for backwards compatibility.
 *
 * JSON keys are short and platform-neutral (no camelCase field names).
 * Compatible with the iOS app which uses the same schema.
 */
object FoodShare {

    const val SCHEME = "nutritrack"
    const val HOST = "food"
    // Public HTTPS redirect (GitHub Pages) — makes the link clickable in messengers.
    private const val WEB_HOST = "anarhi916.github.io"
    private const val WEB_PATH = "/nutritrack/"
    private const val WEB_BASE = "https://$WEB_HOST$WEB_PATH"

    private val gson = Gson()

    fun buildShareLink(nameRu: String, nameEn: String, nutrients: NutrientData): String {
        val payload = mapOf(
            "v" to 1,
            "name" to nameRu,
            "name_en" to nameEn,
            "cal" to nutrients.calories,
            "p" to nutrients.protein,
            "f" to nutrients.fat,
            "c" to nutrients.carbs,
            "fiber" to nutrients.fiber,
            "sat_f" to nutrients.saturatedFat,
            "mono_f" to nutrients.monounsaturatedFat,
            "poly_f" to nutrients.polyunsaturatedFat,
            "chol" to nutrients.cholesterol,
            "va" to nutrients.vitaminA,
            "vb1" to nutrients.vitaminB1,
            "vb2" to nutrients.vitaminB2,
            "vb3" to nutrients.vitaminB3,
            "vb5" to nutrients.vitaminB5,
            "vb6" to nutrients.vitaminB6,
            "vb7" to nutrients.vitaminB7,
            "vb9" to nutrients.vitaminB9,
            "vb12" to nutrients.vitaminB12,
            "vc" to nutrients.vitaminC,
            "vd" to nutrients.vitaminD,
            "ve" to nutrients.vitaminE,
            "vk" to nutrients.vitaminK,
            "ca" to nutrients.calcium,
            "fe" to nutrients.iron,
            "mg" to nutrients.magnesium,
            "ph" to nutrients.phosphorus,
            "k" to nutrients.potassium,
            "na" to nutrients.sodium,
            "zn" to nutrients.zinc,
            "cu" to nutrients.copper,
            "mn" to nutrients.manganese,
            "se" to nutrients.selenium,
            "iod" to nutrients.iodine
        )
        val json = gson.toJson(payload)
        val encoded = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "${WEB_BASE}?v=1&d=$encoded"
    }

    fun parseShareLink(uri: android.net.Uri): SharedFood? {
        // Accept both the custom scheme (nutritrack://food) and the HTTPS redirect
        // page (https://anarhi916.github.io/nutritrack/).
        val isCustomScheme = uri.scheme == SCHEME && uri.host == HOST
        val isWebLink = (uri.scheme == "https" || uri.scheme == "http") &&
            uri.host == WEB_HOST && (uri.path ?: "").startsWith(WEB_PATH.trimEnd('/'))
        if (!isCustomScheme && !isWebLink) return null
        val encoded = uri.getQueryParameter("d") ?: return null
        return try {
            val json = String(Base64.decode(encoded, Base64.URL_SAFE), Charsets.UTF_8)
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: return null
            fun d(key: String) = (map[key] as? Number)?.toDouble() ?: 0.0
            SharedFood(
                nameRu = (map["name"] as? String) ?: return null,
                nameEn = (map["name_en"] as? String) ?: "",
                nutrients = NutrientData(
                    calories = d("cal"), protein = d("p"), fat = d("f"), carbs = d("c"),
                    fiber = d("fiber"), saturatedFat = d("sat_f"),
                    monounsaturatedFat = d("mono_f"), polyunsaturatedFat = d("poly_f"),
                    cholesterol = d("chol"),
                    vitaminA = d("va"), vitaminB1 = d("vb1"), vitaminB2 = d("vb2"),
                    vitaminB3 = d("vb3"), vitaminB5 = d("vb5"), vitaminB6 = d("vb6"),
                    vitaminB7 = d("vb7"), vitaminB9 = d("vb9"), vitaminB12 = d("vb12"),
                    vitaminC = d("vc"), vitaminD = d("vd"), vitaminE = d("ve"), vitaminK = d("vk"),
                    calcium = d("ca"), iron = d("fe"), magnesium = d("mg"), phosphorus = d("ph"),
                    potassium = d("k"), sodium = d("na"), zinc = d("zn"), copper = d("cu"),
                    manganese = d("mn"), selenium = d("se"), iodine = d("iod")
                )
            )
        } catch (_: Exception) { null }
    }

    fun shareViaSystem(context: Context, link: String, foodName: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_food_text, foodName, link))
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_food_chooser)))
    }
}

data class SharedFood(
    val nameRu: String,
    val nameEn: String,
    val nutrients: NutrientData
)

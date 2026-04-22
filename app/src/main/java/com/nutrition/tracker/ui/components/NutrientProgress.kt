package com.nutrition.tracker.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.data.NutrientTopFoods
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.ui.theme.ProgressBackground
import com.nutrition.tracker.ui.theme.ProgressGreen
import com.nutrition.tracker.ui.theme.ProgressOrange
import com.nutrition.tracker.ui.theme.ProgressRed
import com.nutrition.tracker.ui.theme.ProgressYellow

@Composable
fun NutrientProgressBar(
    name: String,
    current: Double,
    target: Double,
    unit: String,
    modifier: Modifier = Modifier,
    upperRatio: Double = 1.5,
    nutrientKey: String? = null,
    onClick: (() -> Unit)? = null
) {
    if (target <= 0) return

    var showTopFoods by remember { mutableStateOf(false) }

    val ratio = current / target
    val progress = ratio.coerceIn(0.0, 1.0).toFloat()
    val percent = (ratio * 100).toInt()
    val color = when {
        ratio > upperRatio * 1.3 -> ProgressRed
        ratio > upperRatio -> ProgressOrange
        ratio >= 0.8 -> ProgressGreen
        ratio >= 0.4 -> ProgressYellow
        else -> ProgressRed
    }

    val clickModifier = if (onClick != null) modifier.clickable { onClick() } else modifier

    if (showTopFoods && nutrientKey != null) {
        NutrientTopFoodsDialog(
            nutrientKey = nutrientKey,
            nutrientName = name,
            onDismiss = { showTopFoods = false }
        )
    }

    Column(modifier = clickModifier.padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (nutrientKey != null && NutrientTopFoods.data.containsKey(nutrientKey)) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "Топ продуктов",
                        modifier = Modifier.size(14.dp).clickable { showTopFoods = true },
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(name, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${"%.1f".format(current)} / ${"%.1f".format(target)} $unit ($percent%)",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = ProgressBackground,
        )
    }
}

@Composable
fun NutrientTopFoodsDialog(
    nutrientKey: String,
    nutrientName: String,
    onDismiss: () -> Unit
) {
    val info = NutrientTopFoods.data[nutrientKey] ?: return onDismiss()
    val sortedFoods = remember(info) { info.foods.sortedByDescending { it.per100g } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Топ-15: $nutrientName") },
        text = {
            Column {
                Text(
                    "Содержание на 100 г продукта (% от дневной нормы)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(sortedFoods) { index, food ->
                        val pct = if (info.dailyValue > 0) (food.per100g / info.dailyValue * 100).toInt() else 0
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${index + 1}. ${food.name}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${"%.1f".format(food.per100g)} ${info.unit} ($pct% дн.)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
fun MacrosProgressSection(
    totals: NutrientData,
    norms: NutrientData,
    entries: List<FoodEntryEntity> = emptyList(),
    parseNutrients: ((String) -> NutrientData)? = null
) {
    var breakdownKey by remember { mutableStateOf<Pair<String, String>?>(null) }

    val macroKeys = listOf(
        Triple("Калории", "calories", "ккал"),
        Triple("Белки", "protein", "г"),
        Triple("Жиры", "fat", "г"),
        Triple("Углеводы", "carbs", "г"),
        Triple("Клетчатка", "fiber", "г")
    )
    // Upper limits as ratio of target: strict for cal/fat/carbs, lenient for protein/fiber
    val macroUpperRatios = listOf(1.15, 1.8, 1.3, 1.3, 3.0)
    val macroValues = listOf(
        totals.calories to norms.calories,
        totals.protein to norms.protein,
        totals.fat to norms.fat,
        totals.carbs to norms.carbs,
        totals.fiber to norms.fiber
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            macroKeys.forEachIndexed { index, (name, key, unit) ->
                val (current, target) = macroValues[index]
                val upperRatio = macroUpperRatios[index]
                NutrientProgressBar(
                    name, current, target, unit,
                    upperRatio = upperRatio,
                    nutrientKey = key,
                    onClick = if (parseNutrients != null) {{ breakdownKey = name to key }} else null
                )
            }
        }
    }

    breakdownKey?.let { (name, key) ->
        if (parseNutrients != null) {
            NutrientBreakdownDialog(
                nutrientName = name,
                nutrientKey = key,
                entries = entries,
                parseNutrients = parseNutrients,
                onDismiss = { breakdownKey = null }
            )
        }
    }
}

@Composable
fun VitaminsProgressSection(
    totals: NutrientData,
    norms: NutrientData,
    entries: List<FoodEntryEntity> = emptyList(),
    parseNutrients: ((String) -> NutrientData)? = null
) {
    var breakdownKey by remember { mutableStateOf<Pair<String, String>?>(null) }

    val totalsList = totals.vitaminsList()
    val normsList = norms.vitaminsList()
    val vitaminKeys = listOf(
        "vitaminA", "vitaminB1", "vitaminB2", "vitaminB3", "vitaminB5",
        "vitaminB6", "vitaminB7", "vitaminB9", "vitaminB12",
        "vitaminC", "vitaminD", "vitaminE", "vitaminK"
    )
    // Upper limits: A,D strict (toxic); B-group,C lenient (water-soluble); E,K moderate
    val vitaminUpperRatios = listOf(
        1.3,  // A - toxic excess
        2.0, 2.0, 2.0, 2.0, // B1-B5 - water-soluble, safe
        2.0, 2.0, 2.0, 2.0, // B6-B12 - water-soluble, safe
        2.5,  // C - water-soluble, very safe
        1.3,  // D - toxic excess
        1.5,  // E - moderate
        1.5   // K - moderate
    )

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            totalsList.forEachIndexed { index, (name, value) ->
                val normValue = normsList.getOrNull(index)?.second ?: 0.0
                val key = vitaminKeys.getOrNull(index) ?: ""
                val upperRatio = vitaminUpperRatios.getOrNull(index) ?: 1.5
                NutrientProgressBar(
                    name, value, normValue, "",
                    upperRatio = upperRatio,
                    nutrientKey = key,
                    onClick = if (parseNutrients != null) {{ breakdownKey = name to key }} else null
                )
            }
        }
    }

    breakdownKey?.let { (name, key) ->
        if (parseNutrients != null) {
            NutrientBreakdownDialog(
                nutrientName = name,
                nutrientKey = key,
                entries = entries,
                parseNutrients = parseNutrients,
                onDismiss = { breakdownKey = null }
            )
        }
    }
}

@Composable
fun MineralsProgressSection(
    totals: NutrientData,
    norms: NutrientData,
    entries: List<FoodEntryEntity> = emptyList(),
    parseNutrients: ((String) -> NutrientData)? = null
) {
    var breakdownKey by remember { mutableStateOf<Pair<String, String>?>(null) }

    val totalsList = totals.mineralsList()
    val normsList = norms.mineralsList()
    val mineralKeys = listOf(
        "calcium", "iron", "magnesium", "phosphorus", "potassium",
        "sodium", "zinc", "copper", "manganese", "selenium", "iodine", "chromium"
    )
    // Upper limits: iron,selenium,copper,iodine strict (toxic); sodium strict; rest moderate
    val mineralUpperRatios = listOf(
        1.5,  // calcium
        1.3,  // iron - toxic
        1.5,  // magnesium
        1.5,  // phosphorus
        1.5,  // potassium
        1.2,  // sodium - strict, excess is bad
        1.5,  // zinc
        1.3,  // copper - toxic
        1.5,  // manganese
        1.3,  // selenium - toxic
        1.3,  // iodine - toxic
        1.5   // chromium
    )

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            totalsList.forEachIndexed { index, (name, value) ->
                val normValue = normsList.getOrNull(index)?.second ?: 0.0
                val key = mineralKeys.getOrNull(index) ?: ""
                val upperRatio = mineralUpperRatios.getOrNull(index) ?: 1.5
                NutrientProgressBar(
                    name, value, normValue, "",
                    upperRatio = upperRatio,
                    nutrientKey = key,
                    onClick = if (parseNutrients != null) {{ breakdownKey = name to key }} else null
                )
            }
        }
    }

    breakdownKey?.let { (name, key) ->
        if (parseNutrients != null) {
            NutrientBreakdownDialog(
                nutrientName = name,
                nutrientKey = key,
                entries = entries,
                parseNutrients = parseNutrients,
                onDismiss = { breakdownKey = null }
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Свернуть" else "Развернуть"
            )
        }
    }
}

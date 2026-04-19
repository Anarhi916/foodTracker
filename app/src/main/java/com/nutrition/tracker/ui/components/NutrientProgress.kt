package com.nutrition.tracker.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.ui.theme.ProgressBackground
import com.nutrition.tracker.ui.theme.ProgressGreen
import com.nutrition.tracker.ui.theme.ProgressRed
import com.nutrition.tracker.ui.theme.ProgressYellow

@Composable
fun NutrientProgressBar(
    name: String,
    current: Double,
    target: Double,
    unit: String,
    modifier: Modifier = Modifier
) {
    if (target <= 0) return

    val ratio = (current / target).coerceIn(0.0, 1.5)
    val progress = (current / target).coerceIn(0.0, 1.0).toFloat()
    val percent = (current / target * 100).toInt()
    val color = when {
        ratio > 1.1 -> ProgressRed
        ratio > 0.8 -> ProgressGreen
        else -> ProgressYellow
    }

    Column(modifier = modifier.padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(name, style = MaterialTheme.typography.bodySmall)
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
fun MacrosProgressSection(totals: NutrientData, norms: NutrientData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            NutrientProgressBar("Калории", totals.calories, norms.calories, "ккал")
            NutrientProgressBar("Белки", totals.protein, norms.protein, "г")
            NutrientProgressBar("Жиры", totals.fat, norms.fat, "г")
            NutrientProgressBar("Углеводы", totals.carbs, norms.carbs, "г")
            NutrientProgressBar("Клетчатка", totals.fiber, norms.fiber, "г")
        }
    }
}

@Composable
fun VitaminsProgressSection(totals: NutrientData, norms: NutrientData) {
    val totalsList = totals.vitaminsList()
    val normsList = norms.vitaminsList()

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            totalsList.forEachIndexed { index, (name, value) ->
                val normValue = normsList.getOrNull(index)?.second ?: 0.0
                NutrientProgressBar(name, value, normValue, "")
            }
        }
    }
}

@Composable
fun MineralsProgressSection(totals: NutrientData, norms: NutrientData) {
    val totalsList = totals.mineralsList()
    val normsList = norms.mineralsList()

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            totalsList.forEachIndexed { index, (name, value) ->
                val normValue = normsList.getOrNull(index)?.second ?: 0.0
                NutrientProgressBar(name, value, normValue, "")
            }
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

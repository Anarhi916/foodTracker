package com.nutrition.tracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.NutrientData

@Composable
fun FoodEntriesTable(
    entries: List<FoodEntryEntity>,
    totals: NutrientData,
    parseNutrients: (String) -> NutrientData,
    onSaveWeights: (Map<Long, Double>) -> Unit,
    onDelete: (FoodEntryEntity) -> Unit
) {
    var editMode by remember { mutableStateOf(false) }
    var editedWeights by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var showDeleteConfirm by remember { mutableStateOf<FoodEntryEntity?>(null) }

    showDeleteConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Удалить?") },
            text = { Text("Удалить ${entry.foodName} из дневника?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(entry)
                    showDeleteConfirm = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Отмена") }
            }
        )
    }

    Column {
        // Toolbar
        if (editMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val changes = mutableMapOf<Long, Double>()
                        for ((id, wStr) in editedWeights) {
                            val w = wStr.toDoubleOrNull()
                            if (w != null && w > 0) changes[id] = w
                        }
                        if (changes.isNotEmpty()) onSaveWeights(changes)
                        editMode = false
                        editedWeights = emptyMap()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Сохранить") }
                OutlinedButton(
                    onClick = {
                        editMode = false
                        editedWeights = emptyMap()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Отмена") }
            }
            Spacer(Modifier.height(4.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        editMode = true
                        editedWeights = entries.associate { it.id to it.weightGrams.toInt().toString() }
                    }
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Редактировать")
                }
            }
        }

        // Header
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Продукт", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2.5f))
                Text("Вес", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Ккал", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Б", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                Text("Ж", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                Text("У", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                if (editMode) {
                    Spacer(Modifier.width(36.dp))
                }
            }
        }

        // Rows
        entries.forEach { entry ->
            val nutrients = parseNutrients(entry.nutrientsJson)
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        entry.foodName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(2.5f)
                    )
                    if (editMode) {
                        val weightText = editedWeights[entry.id] ?: entry.weightGrams.toInt().toString()
                        BasicTextField(
                            value = weightText,
                            onValueChange = { editedWeights = editedWeights + (entry.id to it) },
                            textStyle = TextStyle(
                                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                textAlign = TextAlign.End,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .background(
                                            Color.White,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            0.5.dp,
                                            MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    inner()
                                }
                            }
                        )
                    } else {
                        Text(
                            "${entry.weightGrams.toInt()}г",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text("%.0f".format(nutrients.calories), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("%.1f".format(nutrients.protein), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.7f))
                    Text("%.1f".format(nutrients.fat), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.7f))
                    Text("%.1f".format(nutrients.carbs), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.7f))
                    if (editMode) {
                        IconButton(
                            onClick = { showDeleteConfirm = entry },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Удалить",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // Totals
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Итого", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2.5f))
                Text("${entries.sumOf { it.weightGrams }.toInt()}г", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("%.0f".format(totals.calories), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("%.1f".format(totals.protein), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                Text("%.1f".format(totals.fat), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                Text("%.1f".format(totals.carbs), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f))
                if (editMode) {
                    Spacer(Modifier.width(36.dp))
                }
            }
        }
    }
}

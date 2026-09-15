package com.nutrition.tracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.R
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.util.WeightFormat

@Composable
fun FoodConfirmationDialog(
    foodName: String,
    nutrients: NutrientData,
    weight: String? = null,
    onWeightChange: (String) -> Unit = {},
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.confirm_add), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = foodName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                // Editable weight so the user can set/adjust the portion before adding
                // (mirrors iOS's confirm sheet). Nutrients above are recomputed live by the caller.
                if (weight != null) {
                    OutlinedTextField(
                        value = weight,
                        onValueChange = onWeightChange,
                        label = { Text(stringResource(R.string.weight_g)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                NutrientRow(stringResource(R.string.calories), "%.0f %s".format(nutrients.calories, stringResource(R.string.kcal_short)))
                NutrientRow(stringResource(R.string.protein), "%.1f %s".format(nutrients.protein, stringResource(R.string.gram_short)))
                NutrientRow(stringResource(R.string.fat), "%.1f %s".format(nutrients.fat, stringResource(R.string.gram_short)))
                NutrientRow(stringResource(R.string.carbs), "%.1f %s".format(nutrients.carbs, stringResource(R.string.gram_short)))
                NutrientRow(stringResource(R.string.fiber), "%.1f %s".format(nutrients.fiber, stringResource(R.string.gram_short)))

                if (nutrients.fatDetailsList().any { it.second > 0 }) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(stringResource(R.string.fats_details_2), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    nutrients.fatDetailsList().filter { it.second > 0 }.forEach { (nameRes, value) ->
                        NutrientRow(stringResource(nameRes), "%.2f".format(value))
                    }
                }

                if (nutrients.vitaminsList().any { it.second > 0 }) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(stringResource(R.string.vitamins_2), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    nutrients.vitaminsList().filter { it.second > 0 }.forEach { (nameRes, value) ->
                        NutrientRow(stringResource(nameRes), "%.2f".format(value))
                    }
                }

                if (nutrients.mineralsList().any { it.second > 0 }) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(stringResource(R.string.minerals_2), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    nutrients.mineralsList().filter { it.second > 0 }.forEach { (nameRes, value) ->
                        NutrientRow(stringResource(nameRes), "%.2f".format(value))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun EditWeightDialog(
    currentWeight: String,
    onWeightChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_weight)) },
        text = {
            OutlinedTextField(
                value = currentWeight,
                onValueChange = onWeightChange,
                label = { Text(stringResource(R.string.weight_g)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun BarcodeWeightDialog(
    productName: String,
    weight: String,
    onWeightChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(R.string.food_found)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = productName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = onWeightChange,
                    label = { Text(stringResource(R.string.amount_eaten_g)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun PhotoEditDialog(
    foodName: String,
    weight: String,
    onFoodNameChange: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recognized_from_photo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.review_and_edit_if_needed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = foodName,
                    onValueChange = onFoodNameChange,
                    label = { Text(stringResource(R.string.dish_or_products)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = onWeightChange,
                    label = { Text(stringResource(R.string.portion_weight_g)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.analyze)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun NutrientRow(name: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun NutrientBreakdownDialog(
    nutrientName: String,
    nutrientKey: String,
    entries: List<FoodEntryEntity>,
    parseNutrients: (String) -> NutrientData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val items = entries.map { entry ->
        val nutrients = parseNutrients(entry.nutrientsJson)
        Triple(entry.foodName, entry.weightGrams, nutrients.getByKey(nutrientKey))
    }.filter { it.third > 0.0 }

    val total = items.sumOf { it.third }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(nutrientName, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (items.isEmpty()) {
                    Text(stringResource(R.string.no_data), style = MaterialTheme.typography.bodyMedium)
                } else {
                    // Header
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.food),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            stringResource(R.string.qty),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(60.dp)
                        )
                        Text(
                            "%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(45.dp)
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    items.sortedByDescending { it.third }.forEach { (name, weight, value) ->
                        val pct = if (total > 0) (value / total * 100).toInt() else 0
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Text(
                                "$name (${WeightFormat.short(context, weight)})",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "%.2f".format(value),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(60.dp)
                            )
                            Text(
                                "$pct%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(45.dp)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.total),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "%.2f".format(total),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(60.dp)
                        )
                        Text(
                            "100%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(45.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

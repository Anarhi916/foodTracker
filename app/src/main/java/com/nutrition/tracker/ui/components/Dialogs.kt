package com.nutrition.tracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.data.model.NutrientData

@Composable
fun FoodConfirmationDialog(
    foodName: String,
    nutrients: NutrientData,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Подтвердить добавление", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = foodName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                NutrientRow("Калории", "%.0f ккал".format(nutrients.calories))
                NutrientRow("Белки", "%.1f г".format(nutrients.protein))
                NutrientRow("Жиры", "%.1f г".format(nutrients.fat))
                NutrientRow("Углеводы", "%.1f г".format(nutrients.carbs))
                NutrientRow("Клетчатка", "%.1f г".format(nutrients.fiber))

                if (nutrients.vitaminsList().any { it.second > 0 }) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Витамины:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    nutrients.vitaminsList().filter { it.second > 0 }.forEach { (name, value) ->
                        NutrientRow(name, "%.2f".format(value))
                    }
                }

                if (nutrients.mineralsList().any { it.second > 0 }) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Минералы:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    nutrients.mineralsList().filter { it.second > 0 }.forEach { (name, value) ->
                        NutrientRow(name, "%.2f".format(value))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Добавить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
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
        title = { Text("Изменить вес") },
        text = {
            OutlinedTextField(
                value = currentWeight,
                onValueChange = onWeightChange,
                label = { Text("Вес (г)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
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
    title: String = "Найден продукт"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
                    label = { Text("Вес употреблённого (г)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Добавить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
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

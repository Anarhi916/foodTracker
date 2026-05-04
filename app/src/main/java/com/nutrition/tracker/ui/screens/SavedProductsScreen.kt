package com.nutrition.tracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrition.tracker.data.db.FoodCacheEntity
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedProductsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val cachedFoods by viewModel.cachedFoods.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf<FoodCacheEntity?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<FoodCacheEntity?>(null) }
    var editNutrients by remember { mutableStateOf(NutrientData()) }
    var editNameRu by remember { mutableStateOf("") }
    var editNameEn by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var quickAddEntry by remember { mutableStateOf<FoodCacheEntity?>(null) }
    var quickAddWeight by remember { mutableStateOf("100") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredFoods = remember(cachedFoods, searchQuery) {
        if (searchQuery.isBlank()) cachedFoods
        else {
            val q = searchQuery.lowercase()
            cachedFoods.filter {
                it.keyOriginal.lowercase().contains(q) || it.keyEn.lowercase().contains(q)
            }
        }
    }

    // Quick-add to today dialog
    quickAddEntry?.let { entry ->
        val nutrients = try {
            com.google.gson.Gson().fromJson(entry.nutrientsPer100gJson, NutrientData::class.java)
        } catch (_: Exception) { NutrientData() }

        AlertDialog(
            onDismissRequest = { quickAddEntry = null },
            title = { Text("Добавить в приём пищи") },
            text = {
                Column {
                    Text(entry.keyOriginal, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = quickAddWeight,
                        onValueChange = { quickAddWeight = it },
                        label = { Text("Вес (г)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val w = quickAddWeight.toDoubleOrNull() ?: 0.0
                    if (w > 0) {
                        val factor = w / 100.0
                        Text(
                            "%.0f ккал • Б %.1f • Ж %.1f • У %.1f".format(
                                nutrients.calories * factor, nutrients.protein * factor,
                                nutrients.fat * factor, nutrients.carbs * factor
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val w = quickAddWeight.toDoubleOrNull()
                    if (w != null && w > 0) {
                        viewModel.addCachedFoodToToday(entry, w)
                        quickAddEntry = null
                        quickAddWeight = "100"
                    }
                }) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { quickAddEntry = null }) { Text("Отмена") }
            }
        )
    }

    // Delete confirmation dialog
    showDeleteConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Удалить?") },
            text = { Text("Удалить «${entry.keyOriginal}» из кеша?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCachedFood(entry)
                    showDeleteConfirm = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Отмена") }
            }
        )
    }

    // Clear all confirmation dialog
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Очистить кеш?") },
            text = { Text("Удалить все ${cachedFoods.size} сохранённых продуктов? Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllCachedFoods()
                    showClearAllConfirm = false
                }) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text("Отмена") }
            }
        )
    }

    // Nutrient fields definition (shared between edit and add dialogs)
    data class Field(val label: String, val unit: String, val get: (NutrientData) -> Double, val set: (NutrientData, Double) -> NutrientData)
    val fields = remember {
        listOf(
            Field("Калории", "ккал", { it.calories }, { n, v -> n.copy(calories = v) }),
            Field("Белки", "г", { it.protein }, { n, v -> n.copy(protein = v) }),
            Field("Жиры", "г", { it.fat }, { n, v -> n.copy(fat = v) }),
            Field("Углеводы", "г", { it.carbs }, { n, v -> n.copy(carbs = v) }),
            Field("Клетчатка", "г", { it.fiber }, { n, v -> n.copy(fiber = v) }),
            Field("Витамин A", "мкг", { it.vitaminA }, { n, v -> n.copy(vitaminA = v) }),
            Field("Витамин B1", "мг", { it.vitaminB1 }, { n, v -> n.copy(vitaminB1 = v) }),
            Field("Витамин B2", "мг", { it.vitaminB2 }, { n, v -> n.copy(vitaminB2 = v) }),
            Field("Витамин B3", "мг", { it.vitaminB3 }, { n, v -> n.copy(vitaminB3 = v) }),
            Field("Витамин B5", "мг", { it.vitaminB5 }, { n, v -> n.copy(vitaminB5 = v) }),
            Field("Витамин B6", "мг", { it.vitaminB6 }, { n, v -> n.copy(vitaminB6 = v) }),
            Field("Витамин B7", "мкг", { it.vitaminB7 }, { n, v -> n.copy(vitaminB7 = v) }),
            Field("Витамин B9", "мкг", { it.vitaminB9 }, { n, v -> n.copy(vitaminB9 = v) }),
            Field("Витамин B12", "мкг", { it.vitaminB12 }, { n, v -> n.copy(vitaminB12 = v) }),
            Field("Витамин C", "мг", { it.vitaminC }, { n, v -> n.copy(vitaminC = v) }),
            Field("Витамин D", "мкг", { it.vitaminD }, { n, v -> n.copy(vitaminD = v) }),
            Field("Витамин E", "мг", { it.vitaminE }, { n, v -> n.copy(vitaminE = v) }),
            Field("Витамин K", "мкг", { it.vitaminK }, { n, v -> n.copy(vitaminK = v) }),
            Field("Кальций", "мг", { it.calcium }, { n, v -> n.copy(calcium = v) }),
            Field("Железо", "мг", { it.iron }, { n, v -> n.copy(iron = v) }),
            Field("Магний", "мг", { it.magnesium }, { n, v -> n.copy(magnesium = v) }),
            Field("Фосфор", "мг", { it.phosphorus }, { n, v -> n.copy(phosphorus = v) }),
            Field("Калий", "мг", { it.potassium }, { n, v -> n.copy(potassium = v) }),
            Field("Натрий", "мг", { it.sodium }, { n, v -> n.copy(sodium = v) }),
            Field("Цинк", "мг", { it.zinc }, { n, v -> n.copy(zinc = v) }),
            Field("Медь", "мг", { it.copper }, { n, v -> n.copy(copper = v) }),
            Field("Марганец", "мг", { it.manganese }, { n, v -> n.copy(manganese = v) }),
            Field("Селен", "мкг", { it.selenium }, { n, v -> n.copy(selenium = v) }),
            Field("Йод", "мкг", { it.iodine }, { n, v -> n.copy(iodine = v) }),
            Field("Хром", "мкг", { it.chromium }, { n, v -> n.copy(chromium = v) }),
        )
    }

    // Edit dialog
    editEntry?.let { entry ->
        val texts = remember(entry) { fields.map { mutableStateOf("%.4f".format(it.get(editNutrients)).trimEnd('0').trimEnd('.')) } }

        AlertDialog(
            onDismissRequest = { editEntry = null },
            title = { Text("Редактировать на 100г") },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp)) {
                    OutlinedTextField(
                        value = editNameRu,
                        onValueChange = { editNameRu = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = editNameEn,
                        onValueChange = { editNameEn = it },
                        label = { Text("English name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(fields.size) { i ->
                            OutlinedTextField(
                                value = texts[i].value,
                                onValueChange = { texts[i].value = it },
                                label = { Text("${fields[i].label} (${fields[i].unit})") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    var updated = editNutrients
                    fields.forEachIndexed { i, f ->
                        val v = texts[i].value.replace(",", ".").toDoubleOrNull()
                        if (v != null) updated = f.set(updated, v)
                    }
                    viewModel.updateCachedFoodFull(entry, editNameRu.trim(), editNameEn.trim(), updated)
                    editEntry = null
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { editEntry = null }) { Text("Отмена") }
            }
        )
    }

    // Add new product dialog
    if (showAddDialog) {
        var addNameRu by remember { mutableStateOf("") }
        var addNameEn by remember { mutableStateOf("") }
        var addNutrients by remember { mutableStateOf(NutrientData()) }
        val addTexts = remember { fields.map { mutableStateOf("0") } }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Новый продукт (на 100г)") },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp)) {
                    OutlinedTextField(
                        value = addNameRu,
                        onValueChange = { addNameRu = it },
                        label = { Text("Название *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = addNameEn,
                        onValueChange = { addNameEn = it },
                        label = { Text("English name *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(fields.size) { i ->
                            OutlinedTextField(
                                value = addTexts[i].value,
                                onValueChange = { addTexts[i].value = it },
                                label = { Text("${fields[i].label} (${fields[i].unit})") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (addNameRu.isNotBlank() && addNameEn.isNotBlank()) {
                            var nutrients = NutrientData()
                            fields.forEachIndexed { i, f ->
                                val v = addTexts[i].value.replace(",", ".").toDoubleOrNull()
                                if (v != null) nutrients = f.set(nutrients, v)
                            }
                            viewModel.addManualCachedFood(addNameRu.trim(), addNameEn.trim(), nutrients) { }
                            showAddDialog = false
                        }
                    }
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сохранённые продукты") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить продукт",
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    if (cachedFoods.isNotEmpty()) {
                        IconButton(onClick = { showClearAllConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Очистить всё",
                                tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск продукта...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (cachedFoods.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Кеш пуст.\nПродукты сохраняются автоматически\nпри первом добавлении.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Text(
                            "${filteredFoods.size} из ${cachedFoods.size} продуктов",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // Header
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Продукт", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2.5f))
                                Text("Ккал", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
                                Text("Б", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.6f))
                                Text("Ж", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.6f))
                                Text("У", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.6f))
                                Spacer(Modifier.width(92.dp))
                            }
                        }
                    }

                    items(filteredFoods, key = { it.id }) { entry ->
                        val nutrients = try {
                            com.google.gson.Gson().fromJson(entry.nutrientsPer100gJson, NutrientData::class.java)
                        } catch (_: Exception) { NutrientData() }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(2.5f)) {
                                    Text(
                                        entry.keyOriginal,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        entry.keyEn,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text("%.0f".format(nutrients.calories), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.8f))
                                Text("%.1f".format(nutrients.protein), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
                                Text("%.1f".format(nutrients.fat), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
                                Text("%.1f".format(nutrients.carbs), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
                                IconButton(
                                    onClick = { quickAddWeight = "100"; quickAddEntry = entry },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Добавить", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(
                                    onClick = {
                                        editNutrients = nutrients
                                        editNameRu = entry.keyOriginal
                                        editNameEn = entry.keyEn
                                        editEntry = entry
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Редактировать", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { showDeleteConfirm = entry },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Удалить", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

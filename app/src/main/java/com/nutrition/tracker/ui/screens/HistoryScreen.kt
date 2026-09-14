package com.nutrition.tracker.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.R
import com.nutrition.tracker.data.db.FoodCacheEntity
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.ui.components.EditWeightDialog
import com.nutrition.tracker.ui.components.NutrientProgressBar
import com.nutrition.tracker.util.WeightFormat
import com.nutrition.tracker.util.rankFoodSuggestions
import com.nutrition.tracker.viewmodel.MainViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val recentDates by viewModel.recentDates.collectAsState()
    val norms by viewModel.dailyNorms.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.showEditDialog && uiState.editingEntry != null) {
        EditWeightDialog(
            currentWeight = uiState.editWeight,
            onWeightChange = { viewModel.updateEditWeight(it) },
            onConfirm = { viewModel.confirmEditWeight() },
            onDismiss = { viewModel.dismissEditDialog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_14_days)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
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
        if (recentDates.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_data_for_the_last_14_days), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recentDates) { date ->
                    HistoryDayCard(date = date, viewModel = viewModel, norms = norms)
                }
            }
        }
    }
}

@Composable
private fun HistoryDayCard(
    date: String,
    viewModel: MainViewModel,
    norms: NutrientData?
) {
    var expanded by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var editedWeights by remember { mutableStateOf(mapOf<FoodEntryEntity, String>()) }
    var showAddFoodDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val todayLabel = stringResource(R.string.today)
    val yesterdayLabel = stringResource(R.string.yesterday)
    val displayDate = remember(date) {
        try {
            val ld = LocalDate.parse(date)
            val today = LocalDate.now()
            when {
                ld == today -> todayLabel
                ld == today.minusDays(1) -> yesterdayLabel
                else -> ld.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(java.util.Locale.getDefault()))
            }
        } catch (_: Exception) { date }
    }

    val entries by produceState(initialValue = emptyList<FoodEntryEntity>(), key1 = date) {
        viewModel.getEntriesFlowForDate(date).collect { value = it }
    }

    if (showAddFoodDialog) {
        AddFoodToDateDialog(
            date = date,
            viewModel = viewModel,
            onDismiss = { showAddFoodDialog = false }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayDate,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (expanded) {
                    if (isEditing) {
                        TextButton(onClick = {
                            editedWeights = mapOf()
                            isEditing = false
                        }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = {
                            val changes = editedWeights.entries.mapNotNull { (e, w) ->
                                w.replace(",", ".").toDoubleOrNull()?.let { d -> if (d > 0) e.id to d else null }
                            }.toMap()
                            if (changes.isNotEmpty()) viewModel.updateMultipleWeights(changes)
                            editedWeights = mapOf()
                            isEditing = false
                        }) {
                            Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
                        }
                    } else {
                        IconButton(onClick = {
                            editedWeights = entries.associate { it to it.weightGrams.toInt().toString() }
                            isEditing = true
                        }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                IconButton(onClick = {
                    expanded = !expanded
                    if (!expanded) { isEditing = false; editedWeights = mapOf() }
                }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand)
                    )
                }
            }

            if (expanded && entries.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                val dayTotals = entries.fold(NutrientData()) { acc, entry ->
                    acc + viewModel.parseNutrients(entry.nutrientsJson)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryChip(stringResource(R.string.kcal), "%.0f".format(dayTotals.calories))
                    SummaryChip(stringResource(R.string.p), "%.1f %s".format(dayTotals.protein, stringResource(R.string.gram_short)))
                    SummaryChip(stringResource(R.string.f), "%.1f %s".format(dayTotals.fat, stringResource(R.string.gram_short)))
                    SummaryChip(stringResource(R.string.c), "%.1f %s".format(dayTotals.carbs, stringResource(R.string.gram_short)))
                }

                Spacer(Modifier.height(8.dp))

                if (isEditing) {
                    TextButton(
                        onClick = { showAddFoodDialog = true },
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.add))
                    }
                }

                entries.forEach { entry ->
                    val nutrients = viewModel.parseNutrients(entry.nutrientsJson)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.foodName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        if (isEditing) {
                            val weightVal = editedWeights[entry] ?: entry.weightGrams.toInt().toString()
                            Box(
                                modifier = Modifier
                                    .width(54.dp)
                                    .padding(start = 4.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                androidx.compose.foundation.text.BasicTextField(
                                    value = weightVal,
                                    onValueChange = { v -> editedWeights = editedWeights.toMutableMap().also { m -> m[entry] = v } },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
                                )
                            }
                        } else {
                            Text(
                                text = WeightFormat.short(context, entry.weightGrams),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        Text(
                            text = "%.0f %s".format(nutrients.calories, stringResource(R.string.kcal_short)),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                        if (isEditing) {
                            IconButton(
                                onClick = { viewModel.deleteEntry(entry) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (norms != null) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    NutrientProgressBar(stringResource(R.string.calories), dayTotals.calories, norms.calories, stringResource(R.string.kcal_short))
                    NutrientProgressBar(stringResource(R.string.protein), dayTotals.protein, norms.protein, stringResource(R.string.gram_short))
                    NutrientProgressBar(stringResource(R.string.fat), dayTotals.fat, norms.fat, stringResource(R.string.gram_short))
                    NutrientProgressBar(stringResource(R.string.carbs), dayTotals.carbs, norms.carbs, stringResource(R.string.gram_short))
                    NutrientProgressBar(stringResource(R.string.fiber), dayTotals.fiber, norms.fiber, stringResource(R.string.gram_short))
                    NutrientProgressBar(stringResource(R.string.sat_fat), dayTotals.saturatedFat, norms.saturatedFat, stringResource(R.string.gram_short), upperRatio = 1.0)
                    NutrientProgressBar(stringResource(R.string.monounsat), dayTotals.monounsaturatedFat, norms.monounsaturatedFat, stringResource(R.string.gram_short), upperRatio = 3.0)
                    NutrientProgressBar(stringResource(R.string.polyunsat), dayTotals.polyunsaturatedFat, norms.polyunsaturatedFat, stringResource(R.string.gram_short), upperRatio = 3.0)
                    NutrientProgressBar(stringResource(R.string.cholesterol), dayTotals.cholesterol, norms.cholesterol, stringResource(R.string.mg_short), upperRatio = 1.3)
                }
            }

            if (expanded && entries.isEmpty()) {
                Text(
                    stringResource(R.string.no_entries),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                if (isEditing) {
                    TextButton(onClick = { showAddFoodDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.add))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddFoodToDateDialog(
    date: String,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val cachedFoods by viewModel.cachedFoods.collectAsState()
    var foodName by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var cachedFood by remember { mutableStateOf<FoodCacheEntity?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val suggestions = remember(foodName, cachedFoods) {
        if (foodName.length < 2 || cachedFood != null) emptyList()
        else rankFoodSuggestions(
            query = foodName,
            items = cachedFoods,
            keyOriginal = { it.keyOriginal },
            keyEn = { it.keyEn }
        )
    }

    val weightOk = weight.replace(",", ".").toDoubleOrNull()?.let { it > 0 } == true
    val canAdd = foodName.isNotBlank() && weightOk && !isLoading

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.add_food)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                OutlinedTextField(
                    value = foodName,
                    onValueChange = { foodName = it; cachedFood = null },
                    label = { Text(stringResource(R.string.food)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (cachedFood != null) OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    ) else OutlinedTextFieldDefaults.colors()
                )
                if (suggestions.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column {
                            suggestions.take(4).forEachIndexed { idx, entry ->
                                Surface(
                                    onClick = { foodName = entry.keyOriginal; cachedFood = entry },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Text(
                                        entry.keyOriginal,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                if (idx < suggestions.take(4).lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text(stringResource(R.string.gram_short)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.width(140.dp)
                )
                if (isLoading) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.analyzing), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val w = weight.replace(",", ".").toDoubleOrNull() ?: return@TextButton
                    isLoading = true; error = null
                    viewModel.addFoodForDate(
                        name = foodName.trim(),
                        weightGrams = w,
                        date = date,
                        cachedFood = cachedFood,
                        onSuccess = { onDismiss() },
                        onError = { msg -> isLoading = false; error = msg }
                    )
                },
                enabled = canAdd
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = { if (!isLoading) onDismiss() }) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SummaryChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

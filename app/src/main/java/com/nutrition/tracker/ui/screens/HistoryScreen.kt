package com.nutrition.tracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.ui.components.NutrientProgressBar
import com.nutrition.tracker.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val recentDates by viewModel.recentDates.collectAsState()
    val norms by viewModel.dailyNorms.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История (14 дней)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
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
                Text("Нет данных за последние 14 дней", style = MaterialTheme.typography.bodyLarge)
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
    var entries by remember { mutableStateOf<List<FoodEntryEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val displayDate = try {
        val ld = LocalDate.parse(date)
        val today = LocalDate.now()
        when {
            ld == today -> "Сегодня"
            ld == today.minusDays(1) -> "Вчера"
            else -> ld.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        }
    } catch (_: Exception) { date }

    LaunchedEffect(expanded) {
        if (expanded && entries.isEmpty()) {
            scope.launch {
                entries = viewModel.getEntriesForDate(date)
            }
        }
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
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Свернуть" else "Развернуть"
                    )
                }
            }

            if (expanded && entries.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                val dayTotals = entries.fold(NutrientData()) { acc, entry ->
                    acc + viewModel.parseNutrients(entry.nutrientsJson)
                }

                // Summary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryChip("Ккал", "%.0f".format(dayTotals.calories))
                    SummaryChip("Б", "%.1f г".format(dayTotals.protein))
                    SummaryChip("Ж", "%.1f г".format(dayTotals.fat))
                    SummaryChip("У", "%.1f г".format(dayTotals.carbs))
                }

                Spacer(Modifier.height(8.dp))

                // Entries list
                entries.forEach { entry ->
                    val nutrients = viewModel.parseNutrients(entry.nutrientsJson)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = entry.foodName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${entry.weightGrams.toInt()}г",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "%.0f ккал".format(nutrients.calories),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                // Progress if norms available
                if (norms != null) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    NutrientProgressBar("Калории", dayTotals.calories, norms.calories, "ккал")
                    NutrientProgressBar("Белки", dayTotals.protein, norms.protein, "г")
                    NutrientProgressBar("Жиры", dayTotals.fat, norms.fat, "г")
                    NutrientProgressBar("Углеводы", dayTotals.carbs, norms.carbs, "г")
                }
            }

            if (expanded && entries.isEmpty()) {
                Text(
                    "Нет записей",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

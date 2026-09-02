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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.R
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.ui.components.NutrientProgressBar
import com.nutrition.tracker.util.WeightFormat
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
    var entries by remember { mutableStateOf<List<FoodEntryEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val todayLabel = stringResource(R.string.today)
    val yesterdayLabel = stringResource(R.string.yesterday)
    val displayDate = try {
        val ld = LocalDate.parse(date)
        val today = LocalDate.now()
        when {
            ld == today -> todayLabel
            ld == today.minusDays(1) -> yesterdayLabel
            else -> ld.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(java.util.Locale.getDefault()))
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
                        contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand)
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
                    SummaryChip(stringResource(R.string.kcal), "%.0f".format(dayTotals.calories))
                    SummaryChip(stringResource(R.string.p), "%.1f %s".format(dayTotals.protein, stringResource(R.string.gram_short)))
                    SummaryChip(stringResource(R.string.f), "%.1f %s".format(dayTotals.fat, stringResource(R.string.gram_short)))
                    SummaryChip(stringResource(R.string.c), "%.1f %s".format(dayTotals.carbs, stringResource(R.string.gram_short)))
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
                            text = WeightFormat.short(context, entry.weightGrams),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "%.0f %s".format(nutrients.calories, stringResource(R.string.kcal_short)),
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

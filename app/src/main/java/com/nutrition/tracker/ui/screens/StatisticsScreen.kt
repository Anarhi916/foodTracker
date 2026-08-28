package com.nutrition.tracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.R
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.ui.theme.ProgressGreen
import com.nutrition.tracker.ui.theme.ProgressOrange
import com.nutrition.tracker.ui.theme.ProgressRed
import com.nutrition.tracker.ui.theme.ProgressYellow
import com.nutrition.tracker.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class StatPeriod {
    WEEK,
    MONTH,
    CUSTOM
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE
    val displayFormatter = DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(java.util.Locale.getDefault())
    val today = LocalDate.now()

    var selectedPeriod by remember { mutableStateOf(StatPeriod.WEEK) }
    var startDate by remember { mutableStateOf(today.minusDays(6)) }
    var endDate by remember { mutableStateOf(today) }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    var totals by remember { mutableStateOf<NutrientData?>(null) }
    var dailyNorms by remember { mutableStateOf<NutrientData?>(null) }
    var numDays by remember { mutableStateOf(7L) }
    var isLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // CSV content to be written when user picks a save location
    var pendingCsvContent by remember { mutableStateOf<String?>(null) }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            val content = pendingCsvContent ?: return@let
            context.contentResolver.openOutputStream(it)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            pendingCsvContent = null
        }
    }

    // Compute effective dates based on period selection
    val effectiveStart = when (selectedPeriod) {
        StatPeriod.WEEK -> today.minusDays(6)
        StatPeriod.MONTH -> today.minusDays(29)
        StatPeriod.CUSTOM -> startDate
    }
    val effectiveEnd = when (selectedPeriod) {
        StatPeriod.WEEK, StatPeriod.MONTH -> today
        StatPeriod.CUSTOM -> endDate
    }

    // Load data when dates change
    LaunchedEffect(effectiveStart, effectiveEnd) {
        isLoading = true
        val entries = viewModel.getEntriesForDateRange(
            effectiveStart.format(formatter),
            effectiveEnd.format(formatter)
        )
        // Count only days that have at least one entry
        val daysWithEntries = entries.map { it.date }.distinct().size.toLong()
        numDays = if (daysWithEntries > 0) daysWithEntries else 1
        totals = entries.fold(NutrientData()) { acc, entry ->
            acc + viewModel.parseNutrients(entry.nutrientsJson)
        }
        dailyNorms = viewModel.getDailyNormsSync()
        isLoading = false
    }

    // Date pickers
    if (showStartPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = startDate.toEpochDay() * 86400000L
        )
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        startDate = LocalDate.ofEpochDay(it / 86400000L)
                        if (startDate.isAfter(endDate)) endDate = startDate
                    }
                    showStartPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showStartPicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showEndPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = endDate.toEpochDay() * 86400000L
        )
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        endDate = LocalDate.ofEpochDay(it / 86400000L)
                        if (endDate.isBefore(startDate)) startDate = endDate
                    }
                    showEndPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showEndPicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.statistics)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Period selector
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.period),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            StatPeriod.entries.forEachIndexed { index, period ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = StatPeriod.entries.size
                                    ),
                                    onClick = { selectedPeriod = period },
                                    selected = selectedPeriod == period
                                ) {
                                    Text(
                                        when (period) {
                                            StatPeriod.WEEK -> stringResource(R.string.week)
                                            StatPeriod.MONTH -> stringResource(R.string.month)
                                            StatPeriod.CUSTOM -> stringResource(R.string.custom_range)
                                        }
                                    )
                                }
                            }
                        }

                        if (selectedPeriod == StatPeriod.CUSTOM) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showStartPicker = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(startDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                                }
                                OutlinedButton(
                                    onClick = { showEndPicker = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(endDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${effectiveStart.format(displayFormatter)} — ${effectiveEnd.format(displayFormatter)} ($numDays ${stringResource(R.string.days_short)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val entries = viewModel.getEntriesForDateRange(
                                        effectiveStart.format(formatter),
                                        effectiveEnd.format(formatter)
                                    )
                                    val csv = buildString {
                                        append("\uFEFF")
                                        appendLine("Дата,Продукт,Вес (г)")
                                        var lastDate = ""
                                        entries.sortedBy { it.date }.forEach { entry ->
                                            if (lastDate.isNotEmpty() && entry.date != lastDate) {
                                                appendLine("${entry.date},,")
                                            }
                                            lastDate = entry.date
                                            val escapedName = entry.foodName.replace("\"", "\"\"")
                                            appendLine("${entry.date},\"$escapedName\",${entry.weightGrams.toInt()}")
                                        }
                                    }
                                    pendingCsvContent = csv
                                    val fileName = "nutrition_${effectiveStart.format(formatter)}_${effectiveEnd.format(formatter)}.csv"
                                    saveFileLauncher.launch(fileName)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.export_to_excel))
                        }
                    }
                }
            }

            if (isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (totals != null) {
                val t = totals!!
                val normForPeriod = dailyNorms?.let { it * numDays.toDouble() }

                // Macros
                item {
                    NutrientStatCard(
                        title = stringResource(R.string.macros_and_calories),
                        items = t.macrosList(),
                        normItems = normForPeriod?.macrosList(),
                        upperRatios = listOf(1.15, 1.8, 1.3, 1.3, 3.0)
                    )
                }

                // Vitamins
                item {
                    NutrientStatCard(
                        title = stringResource(R.string.vitamins),
                        items = t.vitaminsList(),
                        normItems = normForPeriod?.vitaminsList(),
                        upperRatios = listOf(1.3, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.5, 1.3, 1.5, 1.5)
                    )
                }

                // Minerals
                item {
                    NutrientStatCard(
                        title = stringResource(R.string.minerals_and_trace_elements),
                        items = t.mineralsList(),
                        normItems = normForPeriod?.mineralsList(),
                        upperRatios = listOf(1.5, 1.3, 1.5, 1.5, 1.5, 1.2, 1.5, 1.3, 1.5, 1.3, 1.3)
                    )
                }

                // Fat details
                item {
                    NutrientStatCard(
                        title = stringResource(R.string.fats_details_3),
                        items = t.fatDetailsList(),
                        normItems = normForPeriod?.fatDetailsList(),
                        upperRatios = listOf(1.0, 3.0, 3.0, 1.3)
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun NutrientStatCard(
    title: String,
    items: List<Pair<Int, Double>>,
    normItems: List<Pair<Int, Double>>?,
    upperRatios: List<Double>? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            // Header
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.nutrient),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.actual),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(72.dp)
                )
                if (normItems != null) {
                    Text(
                        stringResource(R.string.target),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(72.dp)
                    )
                    Text(
                        "%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(48.dp)
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            items.forEachIndexed { index, (nameRes, value) ->
                val normValue = normItems?.getOrNull(index)?.second
                val ratio = if (normValue != null && normValue > 0) value / normValue else null
                val pct = ratio?.let { (it * 100).toInt() }
                val upperRatio = upperRatios?.getOrNull(index) ?: 1.5
                val pctColor = when {
                    pct == null -> MaterialTheme.colorScheme.onSurface
                    ratio!! > upperRatio * 1.3 -> ProgressRed
                    ratio > upperRatio -> ProgressOrange
                    ratio >= 0.8 -> ProgressGreen
                    ratio >= 0.4 -> ProgressYellow
                    else -> ProgressRed
                }
                val name = stringResource(nameRes)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        formatNutrientValue(value),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(72.dp)
                    )
                    if (normItems != null) {
                        Text(
                            formatNutrientValue(normValue ?: 0.0),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(72.dp)
                        )
                        Text(
                            if (pct != null) "$pct%" else "—",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = pctColor,
                            modifier = Modifier.width(48.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatNutrientValue(value: Double): String {
    return when {
        value >= 100 -> "%.0f".format(value)
        value >= 1 -> "%.1f".format(value)
        value > 0 -> "%.2f".format(value)
        else -> "0"
    }
}

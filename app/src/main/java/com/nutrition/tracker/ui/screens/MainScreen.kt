package com.nutrition.tracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrition.tracker.data.db.FoodCacheEntity
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.ui.components.*
import com.nutrition.tracker.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToScanner: () -> Unit,
    onNavigateToCamera: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToEditProfile: () -> Unit,
    onNavigateToSavedProducts: () -> Unit = {},
    onNavigateToSupplementScanner: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val entries by viewModel.todayEntries.collectAsStateWithLifecycle()
    val norms by viewModel.dailyNorms.collectAsStateWithLifecycle()
    val totals by viewModel.todayTotals.collectAsStateWithLifecycle()
    val cachedFoods by viewModel.cachedFoods.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var vitaminsExpanded by remember { mutableStateOf(false) }
    var mineralsExpanded by remember { mutableStateOf(false) }

    // Confirmation dialog
    if (uiState.showConfirmDialog && uiState.pendingFood != null) {
        FoodConfirmationDialog(
            foodName = uiState.pendingFood!!.foodName,
            nutrients = uiState.pendingFood!!.nutrients,
            onConfirm = { viewModel.confirmAddFood() },
            onDismiss = { viewModel.dismissConfirmDialog() }
        )
    }

    // Edit weight dialog
    if (uiState.showEditDialog && uiState.editingEntry != null) {
        EditWeightDialog(
            currentWeight = uiState.editWeight,
            onWeightChange = { viewModel.updateEditWeight(it) },
            onConfirm = { viewModel.confirmEditWeight() },
            onDismiss = { viewModel.dismissEditDialog() }
        )
    }

    // Barcode weight dialog
    if (uiState.showBarcodeWeightDialog) {
        BarcodeWeightDialog(
            productName = uiState.barcodeProductName ?: "",
            weight = uiState.barcodeWeight,
            onWeightChange = { viewModel.updateBarcodeWeight(it) },
            onConfirm = { viewModel.confirmBarcodeAdd() },
            onDismiss = { viewModel.dismissBarcodeDialog() },
            title = "Найден продукт"
        )
    }

    // Supplement (BAD) dialog
    if (uiState.showSupplementDialog) {
        SupplementServingsDialog(
            supplementName = uiState.supplementName ?: "",
            servingSize = uiState.supplementServingSize,
            servings = uiState.supplementServings,
            onServingsChange = { viewModel.updateSupplementServings(it) },
            onConfirm = { viewModel.confirmSupplementAdd() },
            onDismiss = { viewModel.dismissSupplementDialog() }
        )
    }

    // Photo edit dialog
    if (uiState.showPhotoEditDialog) {
        PhotoEditDialog(
            foodName = uiState.photoFoodName,
            weight = uiState.photoWeight,
            onFoodNameChange = { viewModel.updatePhotoFoodName(it) },
            onWeightChange = { viewModel.updatePhotoWeight(it) },
            onConfirm = { viewModel.confirmPhotoAnalysis() },
            onDismiss = { viewModel.dismissPhotoEditDialog() }
        )
    }

    // Error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Nutrition Tracker",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Главная") },
                    selected = true,
                    onClick = { scope.launch { drawerState.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("Профиль") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToEditProfile()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                    label = { Text("Сохранённые продукты") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToSavedProducts()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("История") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToHistory()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                    label = { Text("Статистика") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToStatistics()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nutrition Tracker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Меню",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "История",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Food input section
                item {
                    FoodInputSection(
                        foodInput = uiState.foodInput,
                        onFoodInputChange = { viewModel.updateFoodInput(it) },
                        onAnalyze = { viewModel.analyzeFood() },
                        onScanBarcode = onNavigateToScanner,
                        onTakePhoto = onNavigateToCamera,
                        onScanSupplement = onNavigateToSupplementScanner,
                        isLoading = uiState.isLoading,
                        cachedFoods = cachedFoods,
                        onQuickAdd = { entry, weight ->
                            viewModel.addCachedFoodToToday(entry, weight)
                            viewModel.updateFoodInput("")
                        }
                    )
                }

                // Food entries table
                if (entries.isNotEmpty()) {
                    item {
                        Text(
                            "Сегодня",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    item {
                        FoodEntriesTable(
                            entries = entries,
                            totals = totals,
                            parseNutrients = { viewModel.parseNutrients(it) },
                            onSaveWeights = { changes -> viewModel.updateMultipleWeights(changes) },
                            onDelete = { viewModel.deleteEntry(it) }
                        )
                    }
                }

                // Macros progress
                if (norms != null) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "БЖУ и Калории",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }

                    item {
                        MacrosProgressSection(
                            totals = totals, norms = norms!!,
                            entries = entries,
                            parseNutrients = { viewModel.parseNutrients(it) }
                        )
                    }

                    // Vitamins
                    item {
                        SectionHeader(
                            title = "Витамины",
                            expanded = vitaminsExpanded,
                            onToggle = { vitaminsExpanded = !vitaminsExpanded }
                        )
                    }

                    if (vitaminsExpanded) {
                        item {
                            VitaminsProgressSection(
                                totals = totals, norms = norms!!,
                                entries = entries,
                                parseNutrients = { viewModel.parseNutrients(it) }
                            )
                        }
                    }

                    // Minerals
                    item {
                        SectionHeader(
                            title = "Минералы и микроэлементы",
                            expanded = mineralsExpanded,
                            onToggle = { mineralsExpanded = !mineralsExpanded }
                        )
                    }

                    if (mineralsExpanded) {
                        item {
                            MineralsProgressSection(
                                totals = totals, norms = norms!!,
                                entries = entries,
                                parseNutrients = { viewModel.parseNutrients(it) }
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            // Loading overlay
            if (uiState.isLoading) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Анализируем...", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
    } // ModalNavigationDrawer
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoodInputSection(
    foodInput: String,
    onFoodInputChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onScanBarcode: () -> Unit,
    onTakePhoto: () -> Unit,
    onScanSupplement: () -> Unit,
    isLoading: Boolean,
    cachedFoods: List<FoodCacheEntity> = emptyList(),
    onQuickAdd: (FoodCacheEntity, Double) -> Unit = { _, _ -> }
) {
    var quickAddEntry by remember { mutableStateOf<FoodCacheEntity?>(null) }
    var quickAddWeight by remember { mutableStateOf("100") }

    // Quick-add weight dialog
    quickAddEntry?.let { entry ->
        val nutrients = try {
            com.google.gson.Gson().fromJson(entry.nutrientsPer100gJson, NutrientData::class.java)
        } catch (_: Exception) { NutrientData() }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { quickAddEntry = null },
            title = { Text("Добавить в приём пищи") },
            text = {
                Column {
                    Text(entry.keyOriginal, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = quickAddWeight,
                        onValueChange = { quickAddWeight = it },
                        label = { Text("Вес (г)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
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
                        onQuickAdd(entry, w)
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

    // Compute suggestions
    val suggestions = remember(foodInput, cachedFoods) {
        if (foodInput.length < 2) emptyList()
        else {
            val q = foodInput.lowercase()
            cachedFoods.filter {
                it.keyOriginal.lowercase().contains(q) || it.keyEn.lowercase().contains(q)
            }.take(5)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = foodInput,
                onValueChange = onFoodInputChange,
                label = { Text("Что вы съели?") },
                placeholder = { Text("Например: борщ 300г, хлеб 50г") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                minLines = 2
            )

            // Suggestions dropdown
            if (suggestions.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column {
                        suggestions.forEach { entry ->
                            val nutrients = try {
                                com.google.gson.Gson().fromJson(entry.nutrientsPer100gJson, NutrientData::class.java)
                            } catch (_: Exception) { NutrientData() }
                            Surface(
                                onClick = {
                                    quickAddWeight = "100"
                                    quickAddEntry = entry
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.keyOriginal,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "%.0f ккал • Б%.1f Ж%.1f У%.1f /100г".format(
                                                nutrients.calories, nutrients.protein, nutrients.fat, nutrients.carbs
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Добавить",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (entry != suggestions.last()) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onAnalyze,
                enabled = !isLoading && foodInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Добавить")
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onScanBarcode,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Штрих-код")
                }

                OutlinedButton(
                    onClick = onScanSupplement,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("💊")
                }

                OutlinedButton(
                    onClick = onTakePhoto,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Фото")
                }
            }
        }
    }
}

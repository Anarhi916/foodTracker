package com.nutrition.tracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrition.tracker.R
import com.nutrition.tracker.data.db.FoodCacheEntity
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.ui.components.*
import com.nutrition.tracker.util.transliterateToLatin
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
    var fatDetailsExpanded by remember { mutableStateOf(false) }

    // Confirmation dialog
    if (uiState.showConfirmDialog && uiState.pendingFood != null) {
        val pf = uiState.pendingFood!!
        // Show the editable weight field only for manual text entry — the barcode/photo
        // flows already collect the weight in their own dialog beforehand.
        val isManual = uiState.pendingFoodSource == "manual"
        val displayNutrients = if (isManual && pf.weightGrams > 0 && uiState.pendingFoodWeight != pf.weightGrams)
            pf.nutrients * (uiState.pendingFoodWeight / pf.weightGrams)
        else pf.nutrients
        FoodConfirmationDialog(
            foodName = pf.foodName,
            nutrients = displayNutrients,
            weight = if (isManual) uiState.pendingFoodWeightText else null,
            onWeightChange = { viewModel.updatePendingFoodWeight(it) },
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
            title = stringResource(R.string.food_found)
        )
    }

    // QR share import dialog (when a NutriTrack QR was scanned)
    uiState.importedSharedFood?.let { food ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissImportedSharedFood() },
            title = { Text(stringResource(R.string.add_food)) },
            text = {
                Column {
                    Text(food.nameRu, style = MaterialTheme.typography.titleMedium)
                    Text(
                        food.nameEn,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.macro_summary,
                            food.nutrients.calories, food.nutrients.protein,
                            food.nutrients.fat, food.nutrients.carbs
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.importSharedFood() }) {
                    Text(stringResource(R.string.add_to_saved))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissImportedSharedFood() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
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

    // Photo model choice dialog removed — always use paid model

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
                    label = { Text(stringResource(R.string.home)) },
                    selected = true,
                    onClick = { scope.launch { drawerState.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text(stringResource(R.string.profile)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToEditProfile()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                    label = { Text(stringResource(R.string.saved_foods)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToSavedProducts()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text(stringResource(R.string.history)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToHistory()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                    label = { Text(stringResource(R.string.statistics)) },
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
                            contentDescription = stringResource(R.string.menu),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = stringResource(R.string.history),
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
                        onTakePhoto = { onNavigateToCamera() },
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
                            stringResource(R.string.today),
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
                            stringResource(R.string.macros_and_calories),
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
                            title = stringResource(R.string.vitamins),
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
                            title = stringResource(R.string.minerals_and_trace_elements),
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

                    // Fat details
                    item {
                        SectionHeader(
                            title = stringResource(R.string.fats_details_3),
                            expanded = fatDetailsExpanded,
                            onToggle = { fatDetailsExpanded = !fatDetailsExpanded }
                        )
                    }

                    if (fatDetailsExpanded) {
                        item {
                            FatDetailsProgressSection(
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
                            Text(stringResource(R.string.analyzing), style = MaterialTheme.typography.bodyLarge)
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
    isLoading: Boolean,
    cachedFoods: List<FoodCacheEntity> = emptyList(),
    onQuickAdd: (FoodCacheEntity, Double) -> Unit = { _, _ -> }
) {
    var quickAddEntry by remember { mutableStateOf<FoodCacheEntity?>(null) }
    var quickAddWeight by remember { mutableStateOf("100") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Quick-add weight dialog
    quickAddEntry?.let { entry ->
        val nutrients = try {
            com.google.gson.Gson().fromJson(entry.nutrientsPer100gJson, NutrientData::class.java)
        } catch (_: Exception) { NutrientData() }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { quickAddEntry = null },
            title = { Text(stringResource(R.string.add_to_meal)) },
            text = {
                Column {
                    Text(entry.keyOriginal, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = quickAddWeight,
                        onValueChange = { quickAddWeight = it },
                        label = { Text(stringResource(R.string.weight_g)) },
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
                            stringResource(
                                R.string.macro_summary,
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
                }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = {
                TextButton(onClick = { quickAddEntry = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Compute suggestions
    val suggestions = remember(foodInput, cachedFoods) {
        if (foodInput.length < 2) emptyList()
        else {
            val query = com.nutrition.tracker.util.WeightParser.parse(foodInput).first.trim()
            com.nutrition.tracker.util.rankFoodSuggestions(
                query = query.ifEmpty { foodInput },
                items = cachedFoods,
                keyOriginal = { it.keyOriginal },
                keyEn = { it.keyEn },
            )
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
                label = { Text(stringResource(R.string.what_did_you_eat)) },
                placeholder = { Text(stringResource(R.string.hint_food_example)) },
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
                                            stringResource(
                                                R.string.macro_summary_100g,
                                                nutrients.calories, nutrients.protein, nutrients.fat, nutrients.carbs
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(R.string.add),
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
                onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onAnalyze()
                },
                enabled = !isLoading && foodInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.add))
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
                    Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.barcode))
                }

                OutlinedButton(
                    onClick = onTakePhoto,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = stringResource(R.string.photo))
                }
            }
        }
    }
}

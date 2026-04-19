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
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.ui.components.*
import com.nutrition.tracker.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToScanner: () -> Unit,
    onNavigateToCamera: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToEditProfile: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val entries by viewModel.todayEntries.collectAsStateWithLifecycle()
    val norms by viewModel.dailyNorms.collectAsStateWithLifecycle()
    val totals by viewModel.todayTotals.collectAsStateWithLifecycle()

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

    // Barcode/photo weight dialog
    if (uiState.showBarcodeWeightDialog) {
        BarcodeWeightDialog(
            productName = uiState.barcodeProductName ?: "",
            weight = uiState.barcodeWeight,
            onWeightChange = { viewModel.updateBarcodeWeight(it) },
            onConfirm = { viewModel.confirmBarcodeAdd() },
            onDismiss = { viewModel.dismissBarcodeDialog() },
            title = if (uiState.weightDialogSource == "photo") "Распознано по фото" else "Найден продукт"
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nutrition Tracker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = onNavigateToEditProfile) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Профиль",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
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
                        isLoading = uiState.isLoading
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
}

@Composable
private fun FoodInputSection(
    foodInput: String,
    onFoodInputChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onScanBarcode: () -> Unit,
    onTakePhoto: () -> Unit,
    isLoading: Boolean
) {
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

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAnalyze,
                    enabled = !isLoading && foodInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Добавить")
                }

                OutlinedButton(onClick = onScanBarcode, enabled = !isLoading) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Сканировать")
                }

                OutlinedButton(onClick = onTakePhoto, enabled = !isLoading) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Фото")
                }
            }
        }
    }
}

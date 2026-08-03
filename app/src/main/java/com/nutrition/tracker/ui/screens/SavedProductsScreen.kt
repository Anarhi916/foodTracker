package com.nutrition.tracker.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrition.tracker.R
import com.nutrition.tracker.data.db.FoodCacheEntity
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.ui.theme.ProgressOrange
import com.nutrition.tracker.util.FoodShare
import com.nutrition.tracker.util.QrGenerator
import com.nutrition.tracker.util.transliterateToLatin
import com.nutrition.tracker.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedProductsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val cachedFoods by viewModel.cachedFoods.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf<FoodCacheEntity?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<FoodCacheEntity?>(null) }
    var editNutrients by remember { mutableStateOf(NutrientData()) }
    var editNameRu by remember { mutableStateOf("") }
    var editNameEn by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddTypeChooser by remember { mutableStateOf(false) }
    var showAddDishDialog by remember { mutableStateOf(false) }
    var quickAddEntry by remember { mutableStateOf<FoodCacheEntity?>(null) }
    var quickAddWeight by remember { mutableStateOf("100") }
    var searchQuery by remember { mutableStateOf("") }
    var shareChooserEntry by remember { mutableStateOf<FoodCacheEntity?>(null) }
    var qrEntry by remember { mutableStateOf<FoodCacheEntity?>(null) }

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
            title = { Text(stringResource(R.string.add_to_meal)) },
            text = {
                Column {
                    Text(entry.keyOriginal, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = quickAddWeight,
                        onValueChange = { quickAddWeight = it },
                        label = { Text(stringResource(R.string.weight_g)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                        viewModel.addCachedFoodToToday(entry, w)
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

    // Share method chooser
    shareChooserEntry?.let { entry ->
        val nutrients = try {
            com.google.gson.Gson().fromJson(entry.nutrientsPer100gJson, NutrientData::class.java)
        } catch (_: Exception) { NutrientData() }
        AlertDialog(
            onDismissRequest = { shareChooserEntry = null },
            title = { Text(stringResource(R.string.share)) },
            text = {
                Column {
                    Text(entry.keyOriginal, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.send_the_link_via_messenger_or_show_the_),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val link = FoodShare.buildShareLink(entry.keyOriginal, entry.keyEn, nutrients)
                    FoodShare.shareViaSystem(context, link, entry.keyOriginal)
                    shareChooserEntry = null
                }) { Text(stringResource(R.string.via_link)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    qrEntry = entry
                    shareChooserEntry = null
                }) { Text(stringResource(R.string.via_qr_code)) }
            }
        )
    }

    // Full-screen QR display
    qrEntry?.let { entry ->
        val nutrients = try {
            com.google.gson.Gson().fromJson(entry.nutrientsPer100gJson, NutrientData::class.java)
        } catch (_: Exception) { NutrientData() }
        val link = remember(entry.id) { FoodShare.buildShareLink(entry.keyOriginal, entry.keyEn, nutrients) }
        val bitmap = remember(link) { QrGenerator.generateBitmap(link, 900) }

        Dialog(onDismissRequest = { qrEntry = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        entry.keyOriginal,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.point_the_app_camera_at_the_code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.qr_code),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                        )
                    } else {
                        Text(
                            stringResource(R.string.couldn_t_generate_qr_code),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(
                            R.string.macro_summary,
                            nutrients.calories, nutrients.protein, nutrients.fat, nutrients.carbs
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { qrEntry = null }) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_2)) },
            text = { Text(stringResource(R.string.delete_from_the_cache, entry.keyOriginal)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCachedFood(entry)
                    showDeleteConfirm = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Clear all confirmation dialog
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.clear_cache_q)) },
            text = { Text(stringResource(R.string.choose_what_to_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllCachedFoods()
                    showClearAllConfirm = false
                }) { Text(stringResource(R.string.delete_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.deleteAllBarcodeEntries()
                        showClearAllConfirm = false
                    }) { Text(stringResource(R.string.barcodes_only)) }
                    TextButton(onClick = { showClearAllConfirm = false }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }

    // Nutrient fields definition (shared between edit and add dialogs)
    data class Field(val label: Int, val unit: Int, val get: (NutrientData) -> Double, val set: (NutrientData, Double) -> NutrientData)
    val fields = remember {
        listOf(
            Field(R.string.n_calories, R.string.kcal_short, { it.calories }, { n, v -> n.copy(calories = v) }),
            Field(R.string.n_protein, R.string.gram_short, { it.protein }, { n, v -> n.copy(protein = v) }),
            Field(R.string.n_fat, R.string.gram_short, { it.fat }, { n, v -> n.copy(fat = v) }),
            Field(R.string.n_sat_fat, R.string.gram_short, { it.saturatedFat }, { n, v -> n.copy(saturatedFat = v) }),
            Field(R.string.n_mono_fat, R.string.gram_short, { it.monounsaturatedFat }, { n, v -> n.copy(monounsaturatedFat = v) }),
            Field(R.string.n_poly_fat, R.string.gram_short, { it.polyunsaturatedFat }, { n, v -> n.copy(polyunsaturatedFat = v) }),
            Field(R.string.n_cholesterol, R.string.mg_short, { it.cholesterol }, { n, v -> n.copy(cholesterol = v) }),
            Field(R.string.n_carbs, R.string.gram_short, { it.carbs }, { n, v -> n.copy(carbs = v) }),
            Field(R.string.n_fiber, R.string.gram_short, { it.fiber }, { n, v -> n.copy(fiber = v) }),
            Field(R.string.n_vit_a, R.string.mcg_short, { it.vitaminA }, { n, v -> n.copy(vitaminA = v) }),
            Field(R.string.n_vit_b1, R.string.mg_short, { it.vitaminB1 }, { n, v -> n.copy(vitaminB1 = v) }),
            Field(R.string.n_vit_b2, R.string.mg_short, { it.vitaminB2 }, { n, v -> n.copy(vitaminB2 = v) }),
            Field(R.string.n_vit_b3, R.string.mg_short, { it.vitaminB3 }, { n, v -> n.copy(vitaminB3 = v) }),
            Field(R.string.n_vit_b5, R.string.mg_short, { it.vitaminB5 }, { n, v -> n.copy(vitaminB5 = v) }),
            Field(R.string.n_vit_b6, R.string.mg_short, { it.vitaminB6 }, { n, v -> n.copy(vitaminB6 = v) }),
            Field(R.string.n_vit_b7, R.string.mcg_short, { it.vitaminB7 }, { n, v -> n.copy(vitaminB7 = v) }),
            Field(R.string.n_vit_b9, R.string.mcg_short, { it.vitaminB9 }, { n, v -> n.copy(vitaminB9 = v) }),
            Field(R.string.n_vit_b12, R.string.mcg_short, { it.vitaminB12 }, { n, v -> n.copy(vitaminB12 = v) }),
            Field(R.string.n_vit_c, R.string.mg_short, { it.vitaminC }, { n, v -> n.copy(vitaminC = v) }),
            Field(R.string.n_vit_d, R.string.mcg_short, { it.vitaminD }, { n, v -> n.copy(vitaminD = v) }),
            Field(R.string.n_vit_e, R.string.mg_short, { it.vitaminE }, { n, v -> n.copy(vitaminE = v) }),
            Field(R.string.n_vit_k, R.string.mcg_short, { it.vitaminK }, { n, v -> n.copy(vitaminK = v) }),
            Field(R.string.n_calcium, R.string.mg_short, { it.calcium }, { n, v -> n.copy(calcium = v) }),
            Field(R.string.n_iron, R.string.mg_short, { it.iron }, { n, v -> n.copy(iron = v) }),
            Field(R.string.n_magnesium, R.string.mg_short, { it.magnesium }, { n, v -> n.copy(magnesium = v) }),
            Field(R.string.n_phosphorus, R.string.mg_short, { it.phosphorus }, { n, v -> n.copy(phosphorus = v) }),
            Field(R.string.n_potassium, R.string.mg_short, { it.potassium }, { n, v -> n.copy(potassium = v) }),
            Field(R.string.n_sodium, R.string.mg_short, { it.sodium }, { n, v -> n.copy(sodium = v) }),
            Field(R.string.n_zinc, R.string.mg_short, { it.zinc }, { n, v -> n.copy(zinc = v) }),
            Field(R.string.n_copper, R.string.mg_short, { it.copper }, { n, v -> n.copy(copper = v) }),
            Field(R.string.n_manganese, R.string.mg_short, { it.manganese }, { n, v -> n.copy(manganese = v) }),
            Field(R.string.n_selenium, R.string.mcg_short, { it.selenium }, { n, v -> n.copy(selenium = v) }),
            Field(R.string.n_iodine, R.string.mcg_short, { it.iodine }, { n, v -> n.copy(iodine = v) }),
        )
    }

    // Edit dialog
    editEntry?.let { entry ->
        val texts = remember(entry) { fields.map { mutableStateOf("%.4f".format(java.util.Locale.US, it.get(editNutrients)).trimEnd('0').trimEnd('.')) } }

        AlertDialog(
            onDismissRequest = { editEntry = null },
            title = { Text(stringResource(R.string.edit_per_100g)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp)) {
                    OutlinedTextField(
                        value = editNameRu,
                        onValueChange = { editNameRu = it },
                        label = { Text(stringResource(R.string.name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = editNameEn,
                        onValueChange = { editNameEn = it },
                        label = { Text(stringResource(R.string.english_name_2)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(fields.size) { i ->
                            OutlinedTextField(
                                value = texts[i].value,
                                onValueChange = { texts[i].value = it },
                                label = { Text("${stringResource(fields[i].label)} (${stringResource(fields[i].unit)})") },
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
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editEntry = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Add type chooser: Product or Dish
    if (showAddTypeChooser) {
        AlertDialog(
            onDismissRequest = { showAddTypeChooser = false },
            title = { Text(stringResource(R.string.what_to_add)) },
            text = { Text(stringResource(R.string.single_product_or_dish)) },
            confirmButton = {
                TextButton(onClick = {
                    showAddTypeChooser = false
                    showAddDishDialog = true
                }) { Text(stringResource(R.string.dish_fallback)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddTypeChooser = false
                    showAddDialog = true
                }) { Text(stringResource(R.string.food)) }
            }
        )
    }

// Custom dish dialog (from ingredients)
    if (showAddDishDialog) {
        AddCustomDishDialog(
            viewModel = viewModel,
            cachedFoods = cachedFoods,
            onDismiss = { showAddDishDialog = false }
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
            title = { Text(stringResource(R.string.new_product_100g)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp)) {
                    OutlinedTextField(
                        value = addNameRu,
                        onValueChange = { addNameRu = it },
                        label = { Text(stringResource(R.string.name_2)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = addNameEn,
                        onValueChange = { addNameEn = it },
                        label = { Text(stringResource(R.string.english_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(fields.size) { i ->
                            OutlinedTextField(
                                value = addTexts[i].value,
                                onValueChange = { addTexts[i].value = it },
                                label = { Text("${stringResource(fields[i].label)} (${stringResource(fields[i].unit)})") },
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
                ) { Text(stringResource(R.string.add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.saved_foods)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showAddTypeChooser = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add),
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    if (cachedFoods.isNotEmpty()) {
                        IconButton(onClick = { showClearAllConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.clear_all),
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_product)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
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
                        stringResource(R.string.cache_empty_full),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            stringResource(R.string.lld_of_lld_foods, filteredFoods.size, cachedFoods.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                            textAlign = TextAlign.Center
                        )
                    }

                    items(filteredFoods, key = { it.id }) { entry ->
                        val nutrients = try {
                            com.google.gson.Gson().fromJson(entry.nutrientsPer100gJson, NutrientData::class.java)
                        } catch (_: Exception) { NutrientData() }

                        // Product card — like on iOS: name / English name / nutrients + 4 round buttons.
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.keyOriginal,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        entry.keyEn,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                Spacer(Modifier.width(8.dp))
                                RoundActionButton(
                                    icon = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.add),
                                    background = MaterialTheme.colorScheme.primary,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    onClick = { quickAddWeight = "100"; quickAddEntry = entry }
                                )
                                Spacer(Modifier.width(8.dp))
                                RoundActionButton(
                                    icon = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.edit),
                                    background = ProgressOrange,
                                    tint = androidx.compose.ui.graphics.Color.White,
                                    onClick = {
                                        editNutrients = nutrients
                                        editNameRu = entry.keyOriginal
                                        editNameEn = entry.keyEn
                                        editEntry = entry
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                RoundActionButton(
                                    icon = Icons.Default.Share,
                                    contentDescription = stringResource(R.string.share),
                                    background = MaterialTheme.colorScheme.primary,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    onClick = { shareChooserEntry = entry }
                                )
                                Spacer(Modifier.width(8.dp))
                                RoundActionButton(
                                    icon = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.delete),
                                    background = MaterialTheme.colorScheme.error,
                                    tint = MaterialTheme.colorScheme.onError,
                                    onClick = { showDeleteConfirm = entry }
                                )
                            }
                        }
                    }
                }
            }
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
                        Text(stringResource(R.string.enriching_data), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        } // Box
    }
}

// Round colored icon button (like on iOS: filled circle + icon inside).
@Composable
private fun RoundActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    background: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
    }
}

private data class IngredientInput(
    val id: Long = System.nanoTime(),
    val name: String = "",
    val weight: String = "",
    val cachedFood: FoodCacheEntity? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomDishDialog(
    viewModel: MainViewModel,
    cachedFoods: List<FoodCacheEntity>,
    onDismiss: () -> Unit
) {
    var dishName by remember { mutableStateOf("") }
    var ingredients by remember { mutableStateOf(listOf(IngredientInput())) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val canSave = dishName.isNotBlank() && ingredients.any {
        it.name.isNotBlank() && (it.weight.replace(",", ".").toDoubleOrNull() ?: 0.0) > 0
    } && !isProcessing

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text(stringResource(R.string.new_dish)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp)) {
                OutlinedTextField(
                    value = dishName,
                    onValueChange = { dishName = it },
                    label = { Text(stringResource(R.string.dish_name_required)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    maxLines = 3
                )
                Text(
                    stringResource(R.string.ingredients),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(ingredients.size) { idx ->
                        val ing = ingredients[idx]
                        val isLast = idx == ingredients.lastIndex

                        val suggestions = remember(ing.name, cachedFoods) {
                            if (ing.name.length < 2 || ing.cachedFood != null) emptyList()
                            else {
                                val q = ing.name.lowercase()
                                val qTranslit = transliterateToLatin(q)
                                cachedFoods.filter {
                                    it.keyOriginal.lowercase().contains(q) ||
                                    it.keyEn.lowercase().contains(q) ||
                                    it.keyOriginal.lowercase().contains(qTranslit) ||
                                    it.keyEn.lowercase().contains(qTranslit)
                                }.take(5)
                            }
                        }

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = ing.name,
                                    onValueChange = { newName ->
                                        ingredients = ingredients.toMutableList().also {
                                            it[idx] = it[idx].copy(name = newName, cachedFood = null)
                                        }
                                    },
                                    label = {
                                        Text(if (ing.cachedFood != null) stringResource(R.string.from_cache) else stringResource(R.string.ingredient))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = if (ing.cachedFood != null) OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                                        unfocusedLabelColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    ) else OutlinedTextFieldDefaults.colors()
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                OutlinedTextField(
                                    value = ing.weight,
                                    onValueChange = { newW ->
                                        ingredients = ingredients.toMutableList().also {
                                            it[idx] = it[idx].copy(weight = newW)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.gram_short)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.width(80.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                if (isLast) {
                                    IconButton(
                                        onClick = { ingredients = ingredients + IngredientInput() },
                                        enabled = !isProcessing
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = stringResource(R.string.add_ingredient),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            ingredients = ingredients.filterIndexed { i, _ -> i != idx }
                                                .ifEmpty { listOf(IngredientInput()) }
                                        },
                                        enabled = !isProcessing
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            if (suggestions.isNotEmpty()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(end = 44.dp),
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
                                                    ingredients = ingredients.toMutableList().also {
                                                        it[idx] = it[idx].copy(
                                                            name = entry.keyOriginal,
                                                            cachedFood = entry
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            entry.keyOriginal,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            stringResource(
                                                                R.string.macro_summary_100g,
                                                                nutrients.calories, nutrients.protein,
                                                                nutrients.fat, nutrients.carbs
                                                            ),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                            if (entry != suggestions.last()) HorizontalDivider()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (isProcessing) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.analyzing_ingredients), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.nutrients_will_be_calculated_automatical),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val valid = ingredients.mapNotNull { ing ->
                        val n = ing.name.trim()
                        val w = ing.weight.replace(",", ".").toDoubleOrNull() ?: 0.0
                        if (n.isNotEmpty() && w > 0) Triple(n, w, ing.cachedFood) else null
                    }
                    if (dishName.isBlank() || valid.isEmpty()) return@TextButton
                    isProcessing = true
                    errorMessage = null
                    viewModel.createCustomDish(
                        name = dishName.trim(),
                        ingredients = valid,
                        onSuccess = {
                            isProcessing = false
                            onDismiss()
                        },
                        onError = { msg ->
                            isProcessing = false
                            errorMessage = msg
                        }
                    )
                },
                enabled = canSave
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isProcessing) { Text(stringResource(R.string.cancel)) }
        }
    )
}

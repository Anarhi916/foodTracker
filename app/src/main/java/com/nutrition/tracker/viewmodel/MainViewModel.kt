package com.nutrition.tracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nutrition.tracker.NutritionApp
import com.nutrition.tracker.data.db.FoodCacheEntity
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.FoodAnalysisResult
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.data.db.UserProfileEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow

data class MainUiState(
    val foodInput: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val pendingFood: FoodAnalysisResult? = null,
    val pendingFoodOriginalInput: String = "",
    val pendingFoodWeight: Double = 0.0,
    val pendingFoodSource: String = "manual",
    val showConfirmDialog: Boolean = false,
    val showEditDialog: Boolean = false,
    val editingEntry: FoodEntryEntity? = null,
    val editWeight: String = "",
    val barcodeProductName: String? = null,
    val barcodeNutrientsPer100g: NutrientData? = null,
    val showBarcodeWeightDialog: Boolean = false,
    val barcodeWeight: String = "",
    val weightDialogSource: String = "barcode",
    val photoBytes: ByteArray? = null,
    // Photo edit dialog
    val showPhotoEditDialog: Boolean = false,
    val photoFoodName: String = "",
    val photoOriginalFoodName: String = "",
    val photoWeight: String = "200",
    // Photo: nutrients per 100g for local recalculation
    val photoNutrientsPer100g: NutrientData? = null,
    val photoFoodNameEn: String = "",
    // QR share import (when a NutriTrack QR is scanned)
    val importedSharedFood: com.nutrition.tracker.util.SharedFood? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as NutritionApp).repository

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    // Emits a new value every time the date changes (checked every 30s)
    private val currentDate: StateFlow<String> = flow {
        while (true) {
            emit(repo.todayDate())
            kotlinx.coroutines.delay(30_000)
        }
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.todayDate())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val todayEntries: StateFlow<List<FoodEntryEntity>> = currentDate
        .flatMapLatest { repo.getEntriesForDate(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyNorms: StateFlow<NutrientData?> = repo.getDailyNorms()
        .map { entity ->
            entity?.let { repo.parseNutrients(it.nutrientsJson) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val hasProfile: StateFlow<Boolean?> = repo.getUserProfile()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val userProfile: StateFlow<UserProfileEntity?> = repo.getUserProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val todayTotals: StateFlow<NutrientData> = todayEntries.map { entries ->
        entries.fold(NutrientData()) { acc, entry ->
            acc + repo.parseNutrients(entry.nutrientsJson)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NutrientData())

    val recentDates: StateFlow<List<String>> = repo.getRecentDates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cachedFoods: StateFlow<List<FoodCacheEntity>> = repo.getAllCachedFoods()
        .map { list -> list.filter { !it.keyOriginal.startsWith("barcode:") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Auto-recalculate norms if fat details are missing (legacy norms before fat breakdown update)
        viewModelScope.launch {
            try {
                val norms = repo.getDailyNormsSync() ?: return@launch
                // If norms have fat > 0 but all fat details are zero → need recalculation
                if (norms.fat > 0 && norms.saturatedFat == 0.0 && norms.monounsaturatedFat == 0.0
                    && norms.polyunsaturatedFat == 0.0 && norms.cholesterol == 0.0) {
                    val profile = repo.getUserProfileSync() ?: return@launch
                    android.util.Log.d("MainViewModel", "Recalculating norms to include fat details")
                    repo.calculateAndSaveNorms(
                        profile.gender, profile.age, profile.weightKg, profile.heightCm, profile.goalsText
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Failed to auto-recalculate norms: ${e.message}")
            }
        }
    }

    fun updateFoodInput(text: String) {
        _uiState.value = _uiState.value.copy(foodInput = text)
    }

    fun analyzeFood() {
        val input = _uiState.value.foodInput.trim()
        if (input.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val results = repo.analyzeFoodText(input)
                if (results.size == 1) {
                    // Single food — show confirm dialog
                    val result = results.first()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        pendingFood = result,
                        pendingFoodOriginalInput = input,
                        pendingFoodWeight = if (result.weightGrams > 0) result.weightGrams else extractWeight(input),
                        pendingFoodSource = "manual",
                        showConfirmDialog = true
                    )
                } else {
                    // Multiple foods — add all directly
                    for (result in results) {
                        val weight = if (result.weightGrams > 0) result.weightGrams else 100.0
                        repo.addFoodEntry(
                            foodName = result.foodName,
                            weightGrams = weight,
                            nutrients = result.nutrients,
                            source = "manual",
                            fromCache = result.fromCache
                        )
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        foodInput = ""
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = getApplication<Application>().getString(com.nutrition.tracker.R.string.analysis_error, e.message ?: "")
                )
            }
        }
    }

    fun confirmAddFood() {
        val state = _uiState.value
        val food = state.pendingFood ?: return
        val newWeight = state.pendingFoodWeight
        // Recalculate nutrients if weight was changed
        val nutrients = if (food.weightGrams > 0 && newWeight != food.weightGrams) {
            food.nutrients * (newWeight / food.weightGrams)
        } else {
            food.nutrients
        }

        viewModelScope.launch {
            repo.addFoodEntry(
                foodName = state.pendingFoodOriginalInput.ifBlank { food.foodName },
                weightGrams = newWeight,
                nutrients = nutrients,
                source = state.pendingFoodSource,
                fromCache = food.fromCache
            )
            _uiState.value = _uiState.value.copy(
                showConfirmDialog = false,
                pendingFood = null,
                pendingFoodOriginalInput = "",
                foodInput = ""
            )
        }
    }

    fun dismissConfirmDialog() {
        _uiState.value = _uiState.value.copy(showConfirmDialog = false, pendingFood = null, pendingFoodOriginalInput = "")
    }

    fun deleteEntry(entry: FoodEntryEntity) {
        viewModelScope.launch { repo.deleteFoodEntry(entry) }
    }

    fun updateMultipleWeights(changes: Map<Long, Double>) {
        viewModelScope.launch {
            for ((id, weight) in changes) {
                repo.updateFoodEntryWeight(id, weight)
            }
        }
    }

    fun showEditWeightDialog(entry: FoodEntryEntity) {
        _uiState.value = _uiState.value.copy(
            showEditDialog = true,
            editingEntry = entry,
            editWeight = entry.weightGrams.toInt().toString()
        )
    }

    fun updateEditWeight(weight: String) {
        _uiState.value = _uiState.value.copy(editWeight = weight)
    }

    fun confirmEditWeight() {
        val state = _uiState.value
        val entry = state.editingEntry ?: return
        val newWeight = state.editWeight.toDoubleOrNull() ?: return

        viewModelScope.launch {
            repo.updateFoodEntryWeight(entry.id, newWeight)
            _uiState.value = _uiState.value.copy(showEditDialog = false, editingEntry = null)
        }
    }

    fun dismissEditDialog() {
        _uiState.value = _uiState.value.copy(showEditDialog = false, editingEntry = null)
    }

    fun getEntriesFlowForDate(date: String): Flow<List<FoodEntryEntity>> =
        repo.getEntriesForDate(date).map { list -> list.filter { it.deletedAt == null } }

    fun addFoodForDate(
        name: String, weightGrams: Double, date: String,
        cachedFood: FoodCacheEntity? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val nutrients = if (cachedFood != null) {
                    repo.parseNutrients(cachedFood.nutrientsPer100gJson) * (weightGrams / 100.0)
                } else {
                    repo.analyzeSingleDish(name, weightGrams).nutrients
                }
                repo.addFoodEntry(date, name, "", weightGrams, nutrients, "manual")
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "")
            }
        }
    }

    // --- Barcode ---
    fun onBarcodeScanned(barcode: String) {
        // Check if the scanned code is actually a NutriTrack share QR — parse and import.
        // This lets the same "Scan" flow work for both product barcodes and shared foods.
        val uri = try { android.net.Uri.parse(barcode) } catch (_: Exception) { null }
        if (uri != null) {
            val shared = com.nutrition.tracker.util.FoodShare.parseShareLink(uri)
            if (shared != null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = null,
                    importedSharedFood = shared
                )
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val result = repo.lookupBarcodeWithCache(barcode)
                if (result != null) {
                    val (name, enrichedPer100g, fromCache) = result
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        barcodeProductName = name,
                        barcodeNutrientsPer100g = enrichedPer100g,
                        showBarcodeWeightDialog = true,
                        barcodeWeight = "100"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = getApplication<Application>().getString(com.nutrition.tracker.R.string.no_food_found_for_barcode, barcode)
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = getApplication<Application>().getString(com.nutrition.tracker.R.string.search_error, e.message ?: "")
                )
            }
        }
    }

    fun updateBarcodeWeight(weight: String) {
        _uiState.value = _uiState.value.copy(barcodeWeight = weight)
    }

    suspend fun lookupBarcodeForIngredient(barcode: String): Pair<String, com.nutrition.tracker.data.db.FoodCacheEntity?>? {
        return try { repo.lookupBarcodeForIngredient(barcode) } catch (e: Exception) { null }
    }

    fun confirmBarcodeAdd() {
        val state = _uiState.value
        val name = state.barcodeProductName ?: return
        val per100g = state.barcodeNutrientsPer100g ?: return
        val weight = state.barcodeWeight.toDoubleOrNull() ?: return
        val nutrients = per100g * (weight / 100.0)

        _uiState.value = _uiState.value.copy(
            showBarcodeWeightDialog = false,
            barcodeProductName = null,
            barcodeNutrientsPer100g = null,
            pendingFood = FoodAnalysisResult(
                foodName = name,
                foodNameEn = name,
                weightGrams = weight,
                nutrients = nutrients,
                fromCache = true
            ),
            pendingFoodWeight = weight,
            pendingFoodSource = state.weightDialogSource,
            showConfirmDialog = true
        )
    }

    fun dismissBarcodeDialog() {
        _uiState.value = _uiState.value.copy(
            showBarcodeWeightDialog = false,
            barcodeProductName = null,
            barcodeNutrientsPer100g = null
        )
    }

    // --- QR share import ---
    fun importSharedFood() {
        val food = _uiState.value.importedSharedFood ?: return
        addManualCachedFood(food.nameRu, food.nameEn.ifBlank { food.nameRu }, food.nutrients) {}
        _uiState.value = _uiState.value.copy(importedSharedFood = null)
    }

    fun dismissImportedSharedFood() {
        _uiState.value = _uiState.value.copy(importedSharedFood = null)
    }

    // --- Photo ---
    fun analyzePhoto(imageBytes: ByteArray) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val result = repo.identifyAndAnalyzeFoodFromPhoto(imageBytes)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    showPhotoEditDialog = true,
                    photoFoodName = result.foodName,
                    photoOriginalFoodName = result.foodName,
                    photoFoodNameEn = result.foodNameEn,
                    photoWeight = result.weightGrams.toInt().toString(),
                    photoNutrientsPer100g = result.nutrients
                )
            } catch (e: Exception) {
                val app = getApplication<Application>()
                val userMessage = when {
                    e.message?.contains("Unable to resolve host") == true ||
                    e.message?.contains("No address associated") == true ->
                        app.getString(com.nutrition.tracker.R.string.no_internet_connection_check_your_networ)
                    e.message?.contains("timeout") == true ->
                        app.getString(com.nutrition.tracker.R.string.request_timed_out_check_your_internet_an)
                    else -> app.getString(com.nutrition.tracker.R.string.photo_recognition_error, e.message ?: "")
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = userMessage
                )
            }
        }
    }

    fun updatePhotoFoodName(name: String) {
        _uiState.value = _uiState.value.copy(photoFoodName = name)
    }

    fun updatePhotoWeight(weight: String) {
        _uiState.value = _uiState.value.copy(photoWeight = weight)
    }

    fun dismissPhotoEditDialog() {
        _uiState.value = _uiState.value.copy(showPhotoEditDialog = false)
    }

    fun confirmPhotoAnalysis() {
        val state = _uiState.value
        val foodDesc = state.photoFoodName.trim()
        val weight = state.photoWeight.trim()
        if (foodDesc.isBlank()) return

        val weightGrams = weight.toDoubleOrNull() ?: 200.0
        val per100g = state.photoNutrientsPer100g
        val nameChanged = foodDesc != state.photoOriginalFoodName.trim()

        if (per100g != null && !nameChanged) {
            // Name not changed: use already obtained nutrients, just recalculate for weight
            val factor = weightGrams / 100.0
            val result = FoodAnalysisResult(
                foodName = foodDesc,
                foodNameEn = state.photoFoodNameEn,
                weightGrams = weightGrams,
                nutrients = per100g * factor,
                fromCache = false
            )
            // Cache only now (after user confirmed the name)
            viewModelScope.launch {
                repo.cacheFoodData(foodDesc, state.photoFoodNameEn, per100g)
            }
            _uiState.value = _uiState.value.copy(
                showPhotoEditDialog = false,
                pendingFood = result,
                pendingFoodWeight = result.weightGrams,
                pendingFoodSource = "photo",
                showConfirmDialog = true,
                photoNutrientsPer100g = null,
                photoFoodNameEn = "",
                photoOriginalFoodName = ""
            )
            return
        }

        // Name was changed (or no cached nutrients): request new nutrients from AI
        _uiState.value = _uiState.value.copy(
            showPhotoEditDialog = false,
            isLoading = true,
            error = null
        )

        viewModelScope.launch {
            try {
                val result = repo.analyzeSingleDish(foodDesc, weightGrams)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    pendingFood = result,
                    pendingFoodWeight = result.weightGrams,
                    pendingFoodSource = "photo",
                    showConfirmDialog = true,
                    photoNutrientsPer100g = null,
                    photoFoodNameEn = "",
                    photoOriginalFoodName = ""
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = getApplication<Application>().getString(com.nutrition.tracker.R.string.analysis_error, e.message ?: "")
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Full reset of UI state on sign-out/account deletion — so unfinished
     *  input and errors don't "leak" into the new session. */
    fun resetTransientState() {
        _uiState.value = MainUiState()
    }

    suspend fun getEntriesForDate(date: String): List<FoodEntryEntity> {
        return repo.getEntriesForDateSync(date)
    }

    suspend fun getEntriesForDateRange(startDate: String, endDate: String): List<FoodEntryEntity> {
        return repo.getEntriesForDateRange(startDate, endDate)
    }

    suspend fun getDailyNormsSync(): NutrientData? {
        return repo.getDailyNormsSync()
    }

    fun saveDailyNorms(nutrients: NutrientData) {
        viewModelScope.launch {
            repo.saveDailyNorms(nutrients)
        }
    }

    fun parseNutrients(json: String): NutrientData = repo.parseNutrients(json)

    private fun extractWeight(text: String): Double {
        return com.nutrition.tracker.util.WeightParser.parse(text).second
    }

    fun updateProfile(gender: String, age: Int, weight: Double, height: Double, goals: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // Only recalculate the (expensive, AI-backed) daily norms when a
                // physiological input actually changed. Language / units are set
                // elsewhere and never reach this method, so they can't trigger it.
                val old = repo.getUserProfileSync()
                val physiologyChanged = old == null ||
                    com.nutrition.tracker.util.Gender.fromStored(gender) != com.nutrition.tracker.util.Gender.fromStored(old.gender) ||
                    age != old.age ||
                    weight != old.weightKg ||
                    height != old.heightCm ||
                    goals.trim() != old.goalsText.trim()

                repo.saveUserProfile(gender, age, weight, height, goals)
                if (physiologyChanged) {
                    repo.calculateAndSaveNorms(gender, age, weight, height, goals)
                }
                _uiState.value = _uiState.value.copy(isLoading = false)
                onComplete()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = getApplication<Application>().getString(com.nutrition.tracker.R.string.profile_update_error, e.message ?: "")
                )
            }
        }
    }

    fun deleteCachedFood(entry: FoodCacheEntity) {
        viewModelScope.launch { repo.deleteCachedFood(entry) }
    }

    fun deleteAllCachedFoods() {
        viewModelScope.launch { repo.deleteAllCachedFoods() }
    }

    fun deleteAllBarcodeEntries() {
        viewModelScope.launch { repo.deleteAllBarcodeEntries() }
    }

    fun updateCachedFood(entry: FoodCacheEntity, nutrients: NutrientData) {
        viewModelScope.launch { repo.updateCachedFood(entry.id, nutrients) }
    }

    fun updateCachedFoodFull(entry: FoodCacheEntity, keyOriginal: String, keyEn: String, nutrients: NutrientData) {
        viewModelScope.launch { repo.updateCachedFoodFull(entry.id, keyOriginal, keyEn, nutrients) }
    }

    fun addManualCachedFood(keyOriginal: String, keyEn: String, nutrients: NutrientData, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                repo.addManualCachedFood(keyOriginal, keyEn, nutrients)
            } catch (e: Exception) {
                onError(e.message ?: getApplication<Application>().getString(com.nutrition.tracker.R.string.error_short))
            }
        }
    }

    fun createCustomDish(
        name: String,
        ingredients: List<Triple<String, Double, FoodCacheEntity?>>,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                var total = NutrientData()
                var totalWeight = 0.0
                for ((ingName, ingWeight, cached) in ingredients) {
                    val trimmed = ingName.trim()
                    if (trimmed.isEmpty() || ingWeight <= 0) continue
                    if (cached != null) {
                        val per100g = com.google.gson.Gson().fromJson(cached.nutrientsPer100gJson, NutrientData::class.java)
                        total = total + per100g * (ingWeight / 100.0)
                    } else {
                        val weightStr = if (ingWeight % 1.0 == 0.0) "${ingWeight.toInt()}г" else "${ingWeight}г"
                        val query = "$trimmed $weightStr"
                        val results = try {
                            repo.analyzeFoodText(query)
                        } catch (e: Exception) {
                            onError(getApplication<Application>().getString(com.nutrition.tracker.R.string.couldn_t_recognize_1_2, trimmed, e.message ?: ""))
                            return@launch
                        }
                        if (results.isEmpty()) {
                            onError(getApplication<Application>().getString(com.nutrition.tracker.R.string.couldn_t_recognize, trimmed))
                            return@launch
                        }
                        for (r in results) total = total + r.nutrients
                    }
                    totalWeight += ingWeight
                }
                if (totalWeight <= 0) {
                    onError(getApplication<Application>().getString(com.nutrition.tracker.R.string.total_ingredient_weight_must_be_greater_))
                    return@launch
                }
                val per100g = total * (100.0 / totalWeight)
                val trimmedName = name.trim()
                repo.addManualCachedFood(trimmedName, trimmedName, per100g)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: getApplication<Application>().getString(com.nutrition.tracker.R.string.error_creating_dish))
            }
        }
    }

    fun addCachedFoodToToday(entry: FoodCacheEntity, weightGrams: Double) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val per100g = repo.enrichFatDetailsForCachedEntry(entry)
                val factor = weightGrams / 100.0
                val nutrients = per100g * factor
                repo.addFoodEntry(
                    foodName = entry.keyOriginal,
                    weightGrams = weightGrams,
                    nutrients = nutrients,
                    source = "manual",
                    fromCache = true
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }
}

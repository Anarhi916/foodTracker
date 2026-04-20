package com.nutrition.tracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nutrition.tracker.NutritionApp
import com.nutrition.tracker.data.db.FoodEntryEntity
import com.nutrition.tracker.data.model.FoodAnalysisResult
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.data.db.UserProfileEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MainUiState(
    val foodInput: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val pendingFood: FoodAnalysisResult? = null,
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
    val photoBytes: ByteArray? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as NutritionApp).repository

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    val todayEntries: StateFlow<List<FoodEntryEntity>> = repo.getTodayEntries()
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

    init {
        viewModelScope.launch { repo.cleanupOldEntries() }
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
                            source = "manual"
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
                    error = "Ошибка анализа: ${e.message}"
                )
            }
        }
    }

    fun confirmAddFood() {
        val state = _uiState.value
        val food = state.pendingFood ?: return

        viewModelScope.launch {
            repo.addFoodEntry(
                foodName = food.foodName,
                weightGrams = state.pendingFoodWeight,
                nutrients = food.nutrients,
                source = state.pendingFoodSource
            )
            _uiState.value = _uiState.value.copy(
                showConfirmDialog = false,
                pendingFood = null,
                foodInput = ""
            )
        }
    }

    fun dismissConfirmDialog() {
        _uiState.value = _uiState.value.copy(showConfirmDialog = false, pendingFood = null)
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

    // --- Barcode ---
    fun onBarcodeScanned(barcode: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val result = repo.lookupBarcode(barcode)
                if (result != null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        barcodeProductName = result.first,
                        barcodeNutrientsPer100g = result.second,
                        showBarcodeWeightDialog = true,
                        barcodeWeight = "100"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Продукт не найден по штрих-коду: $barcode"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Ошибка поиска: ${e.message}"
                )
            }
        }
    }

    fun updateBarcodeWeight(weight: String) {
        _uiState.value = _uiState.value.copy(barcodeWeight = weight)
    }

    fun confirmBarcodeAdd() {
        val state = _uiState.value
        val name = state.barcodeProductName ?: return
        val per100g = state.barcodeNutrientsPer100g ?: return
        val weight = state.barcodeWeight.toDoubleOrNull() ?: return
        val factor = weight / 100.0
        val nutrients = per100g * factor

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                showBarcodeWeightDialog = false,
                barcodeProductName = null,
                barcodeNutrientsPer100g = null,
                isLoading = true
            )
            try {
                val enriched = repo.enrichNutrientsWithAI(name, nutrients, weight)
                repo.addFoodEntry(name, weight, enriched, state.weightDialogSource)
            } catch (e: Exception) {
                // If AI enrichment fails, save with original nutrients
                repo.addFoodEntry(name, weight, nutrients, state.weightDialogSource)
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun dismissBarcodeDialog() {
        _uiState.value = _uiState.value.copy(
            showBarcodeWeightDialog = false,
            barcodeProductName = null,
            barcodeNutrientsPer100g = null
        )
    }

    // --- Photo ---
    fun analyzePhoto(imageBytes: ByteArray) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val result = repo.analyzeFoodPhoto(imageBytes)
                val estimatedWeight = if (result.weightGrams > 0) result.weightGrams.toInt().toString() else "100"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    barcodeProductName = result.foodName,
                    barcodeNutrientsPer100g = result.nutrients,
                    showBarcodeWeightDialog = true,
                    barcodeWeight = estimatedWeight,
                    weightDialogSource = "photo"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Ошибка анализа фото: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    suspend fun getEntriesForDate(date: String): List<FoodEntryEntity> {
        return repo.getEntriesForDateSync(date)
    }

    fun parseNutrients(json: String): NutrientData = repo.parseNutrients(json)

    private fun extractWeight(text: String): Double {
        val regex = Regex("""(\d+)\s*(г|гр|грамм|g|ml|мл)""", RegexOption.IGNORE_CASE)
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    fun updateProfile(gender: String, age: Int, weight: Double, height: Double, goals: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                repo.saveUserProfile(gender, age, weight, height, goals)
                repo.calculateAndSaveNorms(gender, age, weight, height, goals)
                _uiState.value = _uiState.value.copy(isLoading = false)
                onComplete()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Ошибка обновления профиля: ${e.message}"
                )
            }
        }
    }
}

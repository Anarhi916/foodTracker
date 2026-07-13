package com.nutrition.tracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nutrition.tracker.NutritionApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val gender: String = "male",
    val age: String = "",
    val weight: String = "",
    val height: String = "",
    val goalsText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isComplete: Boolean = false
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as NutritionApp).repository

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState

    fun updateGender(gender: String) {
        _uiState.value = _uiState.value.copy(gender = gender)
    }

    fun updateAge(age: String) {
        _uiState.value = _uiState.value.copy(age = age)
    }

    fun updateWeight(weight: String) {
        _uiState.value = _uiState.value.copy(weight = weight)
    }

    fun updateHeight(height: String) {
        _uiState.value = _uiState.value.copy(height = height)
    }

    fun updateGoals(goals: String) {
        _uiState.value = _uiState.value.copy(goalsText = goals)
    }

    /** Submit with already-converted canonical kg/cm (the screen handles units). */
    fun submit(weightKg: Double?, heightCm: Double?) {
        val state = _uiState.value
        val age = state.age.toIntOrNull()
        if (age == null || weightKg == null || heightCm == null) {
            _uiState.value = state.copy(error = getApplication<Application>().getString(com.nutrition.tracker.R.string.enter_valid_age_weight_and_height))
            return
        }
        if (state.goalsText.isBlank()) {
            _uiState.value = state.copy(error = getApplication<Application>().getString(com.nutrition.tracker.R.string.describe_your_goals))
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            try {
                repo.saveUserProfile(state.gender, age, weightKg, heightCm, state.goalsText)
                repo.calculateAndSaveNorms(state.gender, age, weightKg, heightCm, state.goalsText)
                _uiState.value = _uiState.value.copy(isLoading = false, isComplete = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = getApplication<Application>().getString(com.nutrition.tracker.R.string.error_generic, e.message ?: "")
                )
            }
        }
    }
}

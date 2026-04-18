package com.nutrition.tracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nutrition.tracker.NutritionApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val gender: String = "Мужской",
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

    fun updateWeight(weight: String) {
        _uiState.value = _uiState.value.copy(weight = weight)
    }

    fun updateHeight(height: String) {
        _uiState.value = _uiState.value.copy(height = height)
    }

    fun updateGoals(goals: String) {
        _uiState.value = _uiState.value.copy(goalsText = goals)
    }

    fun submit() {
        val state = _uiState.value
        val weight = state.weight.toDoubleOrNull()
        val height = state.height.toDoubleOrNull()
        if (weight == null || height == null) {
            _uiState.value = state.copy(error = "Введите корректные вес и рост")
            return
        }
        if (state.goalsText.isBlank()) {
            _uiState.value = state.copy(error = "Опишите ваши цели")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            try {
                repo.saveUserProfile(state.gender, weight, height, state.goalsText)
                repo.calculateAndSaveNorms(state.gender, weight, height, state.goalsText)
                _uiState.value = _uiState.value.copy(isLoading = false, isComplete = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Ошибка: ${e.message}"
                )
            }
        }
    }
}

package com.nutrition.tracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.viewmodel.OnboardingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) onComplete()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройка профиля") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Заполните данные для расчёта дневной нормы питания",
                    style = MaterialTheme.typography.titleLarge
                )

                // Gender
                Text("Пол", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    listOf("Мужской", "Женский").forEach { gender ->
                        Row(
                            modifier = Modifier
                                .selectable(
                                    selected = uiState.gender == gender,
                                    onClick = { viewModel.updateGender(gender) },
                                    role = Role.RadioButton
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.gender == gender,
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(gender)
                        }
                    }
                }

                // Age
                OutlinedTextField(
                    value = uiState.age,
                    onValueChange = { viewModel.updateAge(it) },
                    label = { Text("Возраст (лет)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Weight
                OutlinedTextField(
                    value = uiState.weight,
                    onValueChange = { viewModel.updateWeight(it) },
                    label = { Text("Вес (кг)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Height
                OutlinedTextField(
                    value = uiState.height,
                    onValueChange = { viewModel.updateHeight(it) },
                    label = { Text("Рост (см)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Goals
                OutlinedTextField(
                    value = uiState.goalsText,
                    onValueChange = { viewModel.updateGoals(it) },
                    label = { Text("Цели, физическая активность, тренировки") },
                    placeholder = {
                        Text("Опишите желаемые результаты, уровень физической активности в течение дня, тренировки...")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    maxLines = 10,
                    minLines = 5
                )

                // Error
                if (uiState.error != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = uiState.error!!,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                // Submit
                Button(
                    onClick = { viewModel.submit() },
                    enabled = !uiState.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Рассчитываем нормы...")
                    } else {
                        Text("Рассчитать нормы питания", style = MaterialTheme.typography.titleMedium)
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

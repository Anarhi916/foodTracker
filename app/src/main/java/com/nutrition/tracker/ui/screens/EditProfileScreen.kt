package com.nutrition.tracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Профиль", "Дневные нормы")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> ProfileDataTab(viewModel = viewModel, onBack = onBack)
                1 -> DailyNormsTab(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun ProfileDataTab(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val profile by viewModel.userProfile.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var gender by remember { mutableStateOf("Мужской") }
    var age by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var goalsText by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(profile) {
        if (!initialized && profile != null) {
            gender = profile!!.gender
            age = profile!!.age.toString()
            weight = profile!!.weightKg.toInt().toString()
            height = profile!!.heightCm.toInt().toString()
            goalsText = profile!!.goalsText
            initialized = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Gender
        Text("Пол", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            listOf("Мужской", "Женский").forEach { g ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = gender == g,
                            onClick = { gender = g },
                            role = Role.RadioButton
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = gender == g, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(g)
                }
            }
        }

        // Age
        OutlinedTextField(
            value = age,
            onValueChange = { age = it },
            label = { Text("Возраст (лет)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Weight
        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it },
            label = { Text("Вес (кг)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Height
        OutlinedTextField(
            value = height,
            onValueChange = { height = it },
            label = { Text("Рост (см)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Goals
        OutlinedTextField(
            value = goalsText,
            onValueChange = { goalsText = it },
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
        val errorText = localError ?: uiState.error
        if (errorText != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = errorText,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Submit
        Button(
            onClick = {
                val a = age.toIntOrNull()
                val w = weight.toDoubleOrNull()
                val h = height.toDoubleOrNull()
                if (a == null || w == null || h == null) {
                    localError = "Введите корректные возраст, вес и рост"
                    return@Button
                }
                if (goalsText.isBlank()) {
                    localError = "Опишите ваши цели"
                    return@Button
                }
                localError = null
                viewModel.updateProfile(gender, a, w, h, goalsText, onComplete = onBack)
            },
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
                Text("Пересчитываем нормы...")
            } else {
                Text("Сохранить и пересчитать", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun DailyNormsTab(viewModel: MainViewModel) {
    val norms by viewModel.dailyNorms.collectAsState()
    var isEditing by remember { mutableStateOf(false) }
    // Map of key -> edited string value
    var editedValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Reset edited values when entering edit mode
    LaunchedEffect(isEditing) {
        if (isEditing && norms != null) {
            editedValues = norms!!.allNutrientsList().associate { (key, _, value) ->
                key to formatEditValue(value)
            }
        }
    }

    if (norms == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Нормы ещё не рассчитаны.\nЗаполните профиль и нажмите «Сохранить и пересчитать».",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    val currentNorms = norms!!

    Column(modifier = Modifier.fillMaxSize()) {
        // Edit/Save button row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (isEditing) {
                OutlinedButton(
                    onClick = { isEditing = false },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Отмена")
                }
                Button(
                    onClick = {
                        var updated = currentNorms
                        for ((key, strVal) in editedValues) {
                            val num = strVal.toDoubleOrNull() ?: continue
                            updated = updated.withUpdatedKey(key, num)
                        }
                        viewModel.saveDailyNorms(updated)
                        isEditing = false
                    }
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Сохранить")
                }
            } else {
                OutlinedButton(onClick = { isEditing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Редактировать")
                }
            }
        }

        // Nutrient list
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val allNutrients = currentNorms.allNutrientsList()

            // Group headers
            NormSectionHeader("БЖУ и Калории")
            allNutrients.take(5).forEach { (key, label, value) ->
                NormRow(
                    label = label,
                    value = value,
                    isEditing = isEditing,
                    editValue = editedValues[key] ?: "",
                    onEditValueChange = { editedValues = editedValues + (key to it) }
                )
            }

            Spacer(Modifier.height(8.dp))
            NormSectionHeader("Витамины")
            allNutrients.drop(5).take(13).forEach { (key, label, value) ->
                NormRow(
                    label = label,
                    value = value,
                    isEditing = isEditing,
                    editValue = editedValues[key] ?: "",
                    onEditValueChange = { editedValues = editedValues + (key to it) }
                )
            }

            Spacer(Modifier.height(8.dp))
            NormSectionHeader("Минералы и микроэлементы")
            allNutrients.drop(18).forEach { (key, label, value) ->
                NormRow(
                    label = label,
                    value = value,
                    isEditing = isEditing,
                    editValue = editedValues[key] ?: "",
                    onEditValueChange = { editedValues = editedValues + (key to it) }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun NormSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    HorizontalDivider()
}

@Composable
private fun NormRow(
    label: String,
    value: Double,
    isEditing: Boolean,
    editValue: String,
    onEditValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (isEditing) {
            OutlinedTextField(
                value = editValue,
                onValueChange = onEditValueChange,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.width(100.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                text = formatDisplayValue(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun formatEditValue(value: Double): String {
    return when {
        value == 0.0 -> "0"
        value >= 100 -> "%.0f".format(value)
        value >= 1 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }
}

private fun formatDisplayValue(value: Double): String {
    return when {
        value >= 100 -> "%.0f".format(value)
        value >= 1 -> "%.1f".format(value)
        value > 0 -> "%.2f".format(value)
        else -> "0"
    }
}

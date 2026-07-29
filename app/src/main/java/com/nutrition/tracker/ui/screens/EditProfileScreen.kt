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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.R
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.util.UnitSystem
import com.nutrition.tracker.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.profile), stringResource(R.string.daily_targets))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember { context.applicationContext as com.nutrition.tracker.NutritionApp }
    val authManager = remember { app.authManager }
    val syncManager = remember { app.syncManager }
    var isSyncing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // Принудительная синхронизация (push + pull).
                    IconButton(
                        onClick = {
                            if (isSyncing) return@IconButton
                            scope.launch {
                                isSyncing = true
                                syncManager.forceSyncNow()
                                isSyncing = false
                            }
                        }
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.sync_now)
                            )
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            authManager.signOut()
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = stringResource(R.string.sign_out)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDataTab(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val profile by viewModel.userProfile.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var gender by remember { mutableStateOf("male") }
    var age by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }       // kg or lb, per unitSystem
    var heightCm by remember { mutableStateOf("") }
    var heightFeet by remember { mutableStateOf("") }
    var heightInches by remember { mutableStateOf("") }
    var goalsText by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var unitSystem by remember { mutableStateOf(UnitSystem.current(context)) }
    val isImperial = unitSystem == UnitSystem.IMPERIAL

    val invalidInputMsg = stringResource(R.string.enter_valid_age_weight_and_height)
    val describeGoalsMsg = stringResource(R.string.describe_your_goals)

    // Fill weight/height text fields from canonical kg/cm in the given unit.
    fun fillBody(weightKg: Double, cm: Double, unit: UnitSystem) {
        if (unit == UnitSystem.IMPERIAL) {
            weight = Math.round(com.nutrition.tracker.util.BodyUnits.kgToPounds(weightKg)).toString()
            val (ft, inch) = com.nutrition.tracker.util.BodyUnits.cmToFeetInches(cm)
            heightFeet = ft.toString(); heightInches = inch.toString()
        } else {
            weight = weightKg.toInt().toString()
            heightCm = cm.toInt().toString()
        }
    }

    // Read current fields interpreted in `unit` → canonical (kg, cm) or null.
    fun currentCanonical(unit: UnitSystem): Pair<Double?, Double?> {
        val w = weight.replace(",", ".").toDoubleOrNull()
        return if (unit == UnitSystem.IMPERIAL) {
            val ft = heightFeet.replace(",", ".").toDoubleOrNull()
            val inch = (if (heightInches.isBlank()) "0" else heightInches).replace(",", ".").toDoubleOrNull()
            val kg = w?.let { com.nutrition.tracker.util.BodyUnits.poundsToKg(it) }
            val cm = if (ft != null && inch != null) com.nutrition.tracker.util.BodyUnits.feetInchesToCm(ft, inch) else null
            kg to cm
        } else {
            w to heightCm.replace(",", ".").toDoubleOrNull()
        }
    }

    LaunchedEffect(profile) {
        if (!initialized && profile != null) {
            gender = com.nutrition.tracker.util.Gender.fromStored(profile!!.gender).storedValue
            age = profile!!.age.toString()
            goalsText = profile!!.goalsText
            fillBody(profile!!.weightKg, profile!!.heightCm, unitSystem)
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
        // Language — applied instantly in-app via AppCompat per-app locale.
        com.nutrition.tracker.ui.components.LanguageSelector()

        // Unit system
        Text(stringResource(R.string.units_setting), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            listOf(
                UnitSystem.METRIC to stringResource(R.string.units_metric),
                UnitSystem.IMPERIAL to stringResource(R.string.units_imperial)
            ).forEach { (option, label) ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = unitSystem == option,
                            onClick = {
                                if (unitSystem != option) {
                                    // Convert current values into the new unit so the
                                    // displayed measurement stays the same.
                                    val (kg, cm) = currentCanonical(unitSystem)
                                    unitSystem = option
                                    UnitSystem.set(context, option)
                                    if (kg != null && cm != null) fillBody(kg, cm, option)
                                }
                            },
                            role = Role.RadioButton
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = unitSystem == option, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }
        }

        // Gender
        Text(stringResource(R.string.sex), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            listOf(
                com.nutrition.tracker.util.Gender.MALE to stringResource(R.string.male),
                com.nutrition.tracker.util.Gender.FEMALE to stringResource(R.string.female)
            ).forEach { (genderOption, label) ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = gender == genderOption.storedValue,
                            onClick = { gender = genderOption.storedValue },
                            role = Role.RadioButton
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = gender == genderOption.storedValue, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }
        }

        // Age
        Text(stringResource(R.string.age_years), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = age,
            onValueChange = { age = it },
            placeholder = { Text(stringResource(R.string.age_years)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Weight
        Text(stringResource(if (isImperial) R.string.weight_lb else R.string.weight_kg), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it },
            placeholder = { Text(stringResource(if (isImperial) R.string.weight_lb else R.string.weight_kg)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Height
        if (isImperial) {
            Text(stringResource(R.string.height_ft_in), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = heightFeet,
                    onValueChange = { heightFeet = it },
                    placeholder = { Text(stringResource(R.string.feet)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = heightInches,
                    onValueChange = { heightInches = it },
                    placeholder = { Text(stringResource(R.string.inches)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Text(stringResource(R.string.height_cm), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = heightCm,
                onValueChange = { heightCm = it },
                placeholder = { Text(stringResource(R.string.height_cm)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Goals
        Text(stringResource(R.string.goals_activity_workouts), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = goalsText,
            onValueChange = { goalsText = it },
            placeholder = {
                Text(stringResource(R.string.goals_placeholder))
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
                val (w, h) = currentCanonical(unitSystem)
                if (a == null || w == null || h == null) {
                    localError = invalidInputMsg
                    return@Button
                }
                if (goalsText.isBlank()) {
                    localError = describeGoalsMsg
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
                Text(stringResource(R.string.recalculating_targets))
            } else {
                Text(stringResource(R.string.save_and_recalculate), style = MaterialTheme.typography.titleMedium)
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
                stringResource(R.string.norms_not_calculated_full),
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
                    Text(stringResource(R.string.cancel))
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
                    Text(stringResource(R.string.save))
                }
            } else {
                OutlinedButton(onClick = { isEditing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.edit))
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
            NormSectionHeader(stringResource(R.string.macros_and_calories))
            allNutrients.take(5).forEach { (key, label, value) ->
                NormRow(
                    label = stringResource(label),
                    value = value,
                    isEditing = isEditing,
                    editValue = editedValues[key] ?: "",
                    onEditValueChange = { editedValues = editedValues + (key to it) }
                )
            }

            Spacer(Modifier.height(8.dp))
            NormSectionHeader(stringResource(R.string.vitamins))
            allNutrients.drop(5).take(13).forEach { (key, label, value) ->
                NormRow(
                    label = stringResource(label),
                    value = value,
                    isEditing = isEditing,
                    editValue = editedValues[key] ?: "",
                    onEditValueChange = { editedValues = editedValues + (key to it) }
                )
            }

            Spacer(Modifier.height(8.dp))
            NormSectionHeader(stringResource(R.string.minerals_and_trace_elements))
            allNutrients.drop(18).forEach { (key, label, value) ->
                NormRow(
                    label = stringResource(label),
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

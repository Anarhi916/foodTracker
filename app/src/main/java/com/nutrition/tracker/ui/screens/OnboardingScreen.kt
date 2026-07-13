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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrition.tracker.R
import com.nutrition.tracker.viewmodel.OnboardingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Weight/height are entered in the selected unit system, converted to
    // canonical kg/cm on submit.
    var unitSystem by remember { mutableStateOf(com.nutrition.tracker.util.UnitSystem.current(context)) }
    val isImperial = unitSystem == com.nutrition.tracker.util.UnitSystem.IMPERIAL
    var weightInput by remember { mutableStateOf("") }   // kg or lb
    var heightCmInput by remember { mutableStateOf("") }
    var heightFeet by remember { mutableStateOf("") }
    var heightInches by remember { mutableStateOf("") }

    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) onComplete()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_setup)) },
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
                // Language — applied instantly in-app via AppCompat per-app locale.
                com.nutrition.tracker.ui.components.LanguageSelector()

                Text(
                    text = stringResource(R.string.enter_your_details_to_calculate_daily_nu),
                    style = MaterialTheme.typography.titleLarge
                )

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
                                    selected = uiState.gender == genderOption.storedValue,
                                    onClick = { viewModel.updateGender(genderOption.storedValue) },
                                    role = Role.RadioButton
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.gender == genderOption.storedValue,
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }

                // Age
                Text(stringResource(R.string.age_years), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = uiState.age,
                    onValueChange = { viewModel.updateAge(it) },
                    placeholder = { Text(stringResource(R.string.age_years)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Units
                Text(stringResource(R.string.units_setting), style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    listOf(
                        com.nutrition.tracker.util.UnitSystem.METRIC to stringResource(R.string.units_metric),
                        com.nutrition.tracker.util.UnitSystem.IMPERIAL to stringResource(R.string.units_imperial)
                    ).forEach { (option, label) ->
                        Row(
                            modifier = Modifier.selectable(
                                selected = unitSystem == option,
                                onClick = {
                                    unitSystem = option
                                    com.nutrition.tracker.util.UnitSystem.set(context, option)
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

                // Weight
                Text(stringResource(if (isImperial) R.string.weight_lb else R.string.weight_kg), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = weightInput,
                    onValueChange = { weightInput = it },
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
                        value = heightCmInput,
                        onValueChange = { heightCmInput = it },
                        placeholder = { Text(stringResource(R.string.height_cm)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Goals
                Text(stringResource(R.string.goals_activity_workouts), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = uiState.goalsText,
                    onValueChange = { viewModel.updateGoals(it) },
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
                    onClick = {
                        val w = weightInput.replace(",", ".").toDoubleOrNull()
                        val weightKg: Double?
                        val heightCm: Double?
                        if (isImperial) {
                            weightKg = w?.let { com.nutrition.tracker.util.BodyUnits.poundsToKg(it) }
                            val ft = heightFeet.replace(",", ".").toDoubleOrNull()
                            val inch = (if (heightInches.isBlank()) "0" else heightInches).replace(",", ".").toDoubleOrNull()
                            heightCm = if (ft != null && inch != null)
                                com.nutrition.tracker.util.BodyUnits.feetInchesToCm(ft, inch) else null
                        } else {
                            weightKg = w
                            heightCm = heightCmInput.replace(",", ".").toDoubleOrNull()
                        }
                        viewModel.submit(weightKg, heightCm)
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
                        Text(stringResource(R.string.calculating_targets))
                    } else {
                        Text(stringResource(R.string.calculate_nutrition_targets), style = MaterialTheme.typography.titleMedium)
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

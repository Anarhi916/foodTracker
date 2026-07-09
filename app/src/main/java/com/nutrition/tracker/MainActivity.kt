package com.nutrition.tracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nutrition.tracker.ui.navigation.Screen
import com.nutrition.tracker.ui.screens.*
import com.nutrition.tracker.ui.theme.NutritionTrackerTheme
import com.nutrition.tracker.util.FoodShare
import com.nutrition.tracker.viewmodel.MainViewModel
import com.nutrition.tracker.viewmodel.OnboardingViewModel
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NutritionTrackerTheme {
                NutritionTrackerApp(intent = intent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Trigger recomposition with new intent by recreating — simplest safe approach
        // for deep link handling when app is already running.
        setContent {
            NutritionTrackerTheme {
                NutritionTrackerApp(intent = intent)
            }
        }
    }
}

@Composable
fun NutritionTrackerApp(intent: Intent? = null) {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = viewModel()
    val onboardingViewModel: OnboardingViewModel = viewModel()

    // Deep link: import shared food
    var importFood by remember { mutableStateOf<com.nutrition.tracker.util.SharedFood?>(null) }
    var importDone by remember { mutableStateOf(false) }
    LaunchedEffect(intent) {
        if (importDone) return@LaunchedEffect
        val uri = intent?.data ?: return@LaunchedEffect
        val food = FoodShare.parseShareLink(uri) ?: return@LaunchedEffect
        importFood = food
    }

    // Import confirmation dialog
    importFood?.let { food ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { importFood = null },
            title = { androidx.compose.material3.Text("Добавить продукт?") },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        food.nameRu,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    androidx.compose.material3.Text(
                        food.nameEn,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Text(
                        "%.0f ккал • Б %.1f г • Ж %.1f г • У %.1f г".format(
                            food.nutrients.calories, food.nutrients.protein,
                            food.nutrients.fat, food.nutrients.carbs
                        ),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    mainViewModel.addManualCachedFood(food.nameRu, food.nameEn, food.nutrients) {}
                    importFood = null
                    importDone = true
                }) { androidx.compose.material3.Text("Добавить в сохранённые") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { importFood = null; importDone = true }) {
                    androidx.compose.material3.Text("Отмена")
                }
            }
        )
    }

    // Determine start destination before showing NavHost
    var startRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val hasProfile = mainViewModel.hasProfile.first { it != null }
        startRoute = if (hasProfile == true) Screen.Main.route else Screen.Onboarding.route
    }

    if (startRoute == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(
        navController = navController,
        startDestination = startRoute!!
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Main.route) {
            MainScreen(
                viewModel = mainViewModel,
                onNavigateToScanner = { navController.navigate(Screen.BarcodeScanner.route) },
                onNavigateToCamera = { navController.navigate(Screen.PhotoCapture.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToEditProfile = { navController.navigate(Screen.EditProfile.route) },
                onNavigateToSavedProducts = { navController.navigate(Screen.SavedProducts.route) },
                onNavigateToSupplementScanner = { navController.navigate(Screen.SupplementScanner.route) },
                onNavigateToStatistics = { navController.navigate(Screen.Statistics.route) }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(viewModel = mainViewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.BarcodeScanner.route) {
            BarcodeScannerScreen(
                onBarcodeScanned = { barcode -> mainViewModel.onBarcodeScanned(barcode) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.SupplementScanner.route) {
            BarcodeScannerScreen(
                onBarcodeScanned = { barcode -> mainViewModel.onSupplementBarcodeScanned(barcode) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.PhotoCapture.route) {
            PhotoCaptureScreen(
                onPhotoTaken = { bytes -> mainViewModel.analyzePhoto(bytes) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.EditProfile.route) {
            EditProfileScreen(viewModel = mainViewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.SavedProducts.route) {
            SavedProductsScreen(viewModel = mainViewModel, onBack = { navController.popBackStack() })
        }

        composable(Screen.Statistics.route) {
            StatisticsScreen(viewModel = mainViewModel, onBack = { navController.popBackStack() })
        }
    }
}

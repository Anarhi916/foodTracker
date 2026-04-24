package com.nutrition.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nutrition.tracker.ui.navigation.Screen
import com.nutrition.tracker.ui.screens.*
import com.nutrition.tracker.ui.theme.NutritionTrackerTheme
import com.nutrition.tracker.viewmodel.MainViewModel
import com.nutrition.tracker.viewmodel.OnboardingViewModel
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NutritionTrackerTheme {
                NutritionTrackerApp()
            }
        }
    }
}

@Composable
fun NutritionTrackerApp() {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = viewModel()
    val onboardingViewModel: OnboardingViewModel = viewModel()

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
                onNavigateToScanner = {
                    navController.navigate(Screen.BarcodeScanner.route)
                },
                onNavigateToCamera = {
                    navController.navigate(Screen.PhotoCapture.route)
                },
                onNavigateToHistory = {
                    navController.navigate(Screen.History.route)
                },
                onNavigateToEditProfile = {
                    navController.navigate(Screen.EditProfile.route)
                },
                onNavigateToSavedProducts = {
                    navController.navigate(Screen.SavedProducts.route)
                },
                onNavigateToSupplementScanner = {
                    navController.navigate(Screen.SupplementScanner.route)
                }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                viewModel = mainViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.BarcodeScanner.route) {
            BarcodeScannerScreen(
                onBarcodeScanned = { barcode ->
                    mainViewModel.onBarcodeScanned(barcode)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.SupplementScanner.route) {
            BarcodeScannerScreen(
                onBarcodeScanned = { barcode ->
                    mainViewModel.onSupplementBarcodeScanned(barcode)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.PhotoCapture.route) {
            PhotoCaptureScreen(
                onPhotoTaken = { bytes ->
                    mainViewModel.analyzePhoto(bytes)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.EditProfile.route) {
            EditProfileScreen(
                viewModel = mainViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.SavedProducts.route) {
            SavedProductsScreen(
                viewModel = mainViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

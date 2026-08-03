package com.nutrition.tracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
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

    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as NutritionApp
    val authManager = app.authManager
    val isSignedIn by authManager.authState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // OAuth redirect — handle both Apple scheme (OAUTH_REDIRECT_SCHEME) and Google scheme.
    // Дедуп по самому URI: тот же redirect (с уже использованным кодом) не обрабатываем
    // повторно — иначе при рекомпозиции/возврате на экран Google отклонит код (invalid_grant)
    // и вход отскочит обратно на логин.
    var handledRedirectUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(intent) {
        val data = intent?.data ?: return@LaunchedEffect
        val scheme = data.scheme ?: return@LaunchedEffect
        val uriStr = data.toString()
        if (uriStr == handledRedirectUri) return@LaunchedEffect
        if (scheme == BuildConfig.OAUTH_REDIRECT_SCHEME ||
            scheme.startsWith("com.googleusercontent.apps.")
        ) {
            handledRedirectUri = uriStr
            authManager.handleRedirect(data)
        }
    }

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
            title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.nutrition.tracker.R.string.add_food)) },
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
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.nutrition.tracker.R.string.cancel))
                }
            }
        )
    }

    // Determine start destination before showing NavHost
    var startRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val hasProfile = mainViewModel.hasProfile.first { it != null }
        startRoute = when {
            !authManager.authState.value -> Screen.Login.route
            hasProfile == true -> Screen.Main.route
            else -> Screen.Onboarding.route
        }
    }

    if (startRoute == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val syncManager = app.syncManager
    val isInitialSyncing by syncManager.isInitialSyncing.collectAsStateWithLifecycle()

    // Успешный вход → full pull (с busy indicator), затем уходим с Login на онбординг/главный.
    LaunchedEffect(isSignedIn) {
        if (isSignedIn && navController.currentDestination?.route == Screen.Login.route) {
            syncManager.pullOnLogin()
            val hasProfile = mainViewModel.hasProfile.first { it != null }
            val target = if (hasProfile == true) Screen.Main.route else Screen.Onboarding.route
            navController.navigate(target) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    // Выход из аккаунта → полный wipe локальных данных + сброс состояния, затем на Login.
    // Стираем и при обычном выходе (не только удалении): иначе при входе ДРУГОГО аккаунта
    // данные прошлого юзера покажутся локально и зальются на сервер через pullOnLogin(since=0).
    LaunchedEffect(isSignedIn) {
        if (!isSignedIn && navController.currentDestination?.route != Screen.Login.route) {
            app.repository.wipeAllLocalData()
            syncManager.resetOnSignOut()
            mainViewModel.resetTransientState()
            onboardingViewModel.reset()
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Аккаунт удалён с другого устройства → уведомление.
    val accountDeleted by authManager.accountDeletedNotice.collectAsStateWithLifecycle()
    if (accountDeleted) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { authManager.setAccountDeletedNotice(false) },
            title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.account_deleted_title)) },
            text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.account_deleted_message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { authManager.setAccountDeletedNotice(false) }) {
                    androidx.compose.material3.Text("OK")
                }
            }
        )
    }

    // Тихая ежедневная синхронизация при возобновлении приложения.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, isSignedIn) {
        lifecycleOwner.lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && isSignedIn) {
                    scope.launch { syncManager.dailySyncIfNeeded() }
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = startRoute!!
    ) {
        composable(Screen.Login.route) {
            LoginScreen(authManager = authManager, context = context)
        }

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onComplete = {
                    // Профиль создан → сразу заливаем на сервер (иначе уйдёт только
                    // при следующей ежедневной синхронизации).
                    scope.launch { syncManager.backgroundSync() }
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

    // Busy indicator при первичной загрузке данных (full pull после логина).
    if (isInitialSyncing) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(com.nutrition.tracker.R.string.loading_your_data),
                    color = androidx.compose.ui.graphics.Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
    }
}

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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
    // Hold the current intent in observable state. onNewIntent updates it to recompose
    // WITHOUT calling setContent again — a second setContent rebuilds the whole Compose tree
    // from scratch (resetting startRoute→null → white spinner flash, and creating a fresh
    // navController whose route reads null → defeats the splash gate). Feeding the intent
    // through state keeps remember{}/navController alive across the OAuth redirect.
    private val intentState = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intentState.value = intent
        setContent {
            NutritionTrackerTheme {
                NutritionTrackerApp(intent = intentState.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState.value = intent   // recompose only; tree + navController survive
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
    // Dedup by the URI itself: the same redirect (with an already-used code) is not processed
    // again — otherwise on recomposition/returning to the screen Google would reject the code
    // (invalid_grant) and the sign-in would bounce back to login.
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
                }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.nutrition.tracker.R.string.add_to_saved)) }
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
        BrandedSplash()
        return
    }

    val syncManager = app.syncManager
    val isInitialSyncing by syncManager.isInitialSyncing.collectAsStateWithLifecycle()
    val authInProgress by authManager.authInProgress.collectAsStateWithLifecycle()
    // Keep the splash up across the WHOLE login→main transition. Three independent covers:
    //   1) isInitialSyncing — during pullOnLogin's data load
    //   2) authInProgress — during the Google token exchange (after the tab closes, before authState flips)
    //   3) navigatingOffLogin — held manually from before pullOnLogin until navigate() lands
    // Plus a guard for the brief window where we're signed in but still on Login (a null
    // current route on a fresh NavHost frame falls back to startRoute). This must ONLY match
    // the Login route — matching "anything != Main/Onboarding" would wrongly show the splash
    // on every secondary screen (EditProfile, History, Statistics, …).
    var navigatingOffLogin by remember { mutableStateOf(false) }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val effectiveRoute = currentRoute ?: startRoute
    val showSplash = isInitialSyncing || authInProgress || navigatingOffLogin ||
        (isSignedIn && effectiveRoute == Screen.Login.route)

    // Successful sign-in → full pull (with busy indicator), then leave Login for onboarding/main.
    LaunchedEffect(isSignedIn) {
        if (isSignedIn && (navController.currentDestination?.route ?: startRoute) == Screen.Login.route) {
            navigatingOffLogin = true
            syncManager.pullOnLogin()
            // Read the DB authoritatively — NOT mainViewModel.hasProfile. That is a
            // WhileSubscribed(5000) StateFlow; the ~10s pull outlives its 5s keep-alive, so its
            // cached value is a stale `false` from startup and `.first { it != null }` would
            // return that stale false → wrongly route a returning user to Onboarding.
            val hasProfile = app.repository.getUserProfileSync() != null
            val target = if (hasProfile) Screen.Main.route else Screen.Onboarding.route
            navController.navigate(target) {
                popUpTo(Screen.Login.route) { inclusive = true }
                launchSingleTop = true
            }
            navigatingOffLogin = false
        }
    }

    // Sign-out → full wipe of local data + state reset, then to Login.
    // We also wipe on a regular sign-out (not just deletion): otherwise when ANOTHER account
    // signs in, the previous user's data would show locally and get uploaded to the server via pullOnLogin(since=0).
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

    // Account deleted from another device → notification.
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

    // Silent daily sync when the app resumes.
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
                    // Profile created → upload to the server right away (otherwise it would only
                    // go out at the next daily sync).
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

    // Full-screen branded splash during the initial data load (hides the login screen under it).
    if (showSplash) {
        BrandedSplash()
    }
    }
}

// Full-screen opaque brand-green splash with the logo + spinner. Shown during the login→main
// transition and while the start route is being resolved, so the login screen never flashes.
@Composable
private fun BrandedSplash() {
    val top = androidx.compose.ui.graphics.Color(0xFF1B9E3E)
    val bottom = androidx.compose.ui.graphics.Color(0xFF147A30)
    Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(top, bottom))
        ),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp)
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.nutrition.tracker.R.drawable.ic_login_logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )
            androidx.compose.material3.Text(
                "Nutrition Tracker",
                color = androidx.compose.ui.graphics.Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(com.nutrition.tracker.R.string.loading_your_data),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )
        }
    }
}

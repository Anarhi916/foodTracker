package com.nutrition.tracker.ui.navigation

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Main : Screen("main")
    data object History : Screen("history")
    data object BarcodeScanner : Screen("barcode_scanner")
    data object PhotoCapture : Screen("photo_capture")
}

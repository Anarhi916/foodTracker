package com.nutrition.tracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Unified corner-radius scale (Material 3). Larger than the default → more modern, softer.
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),   // cards
    large = RoundedCornerShape(20.dp),    // large containers
    extraLarge = RoundedCornerShape(28.dp)
)

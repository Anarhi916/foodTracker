package com.nutrition.tracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Единая шкала скруглений (Material 3). Крупнее дефолта → современнее, мягче.
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),   // карточки
    large = RoundedCornerShape(20.dp),    // крупные контейнеры
    extraLarge = RoundedCornerShape(28.dp)
)

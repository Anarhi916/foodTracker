# Nutrition Tracker — Architecture Cheat Sheet

## Project Overview
Android app (Kotlin, Jetpack Compose, Room, Retrofit). Tracks food intake with nutrients (calories, macros, vitamins, minerals). Uses USDA FDC, OpenFoodFacts, and OpenRouter AI (Gemini models) for food analysis.

## File Structure & Responsibilities

### Data Layer

#### Models
- **`data/model/Models.kt`** — Core data classes:
  - `NutrientData` — 29 nutrient fields (cal, protein, fat, carbs, fiber, 13 vitamins, 11 minerals). Has operators `+`, `*`, lists (`macrosList()`, `vitaminsList()`, `mineralsList()`), `getByKey()`, `withUpdatedKey()`, `allNutrientsList()`
  - `FoodAnalysisResult` — foodName, foodNameEn, weightGrams, nutrients, fromCache

#### Database (Room, version 3)
- **`data/db/AppDatabase.kt`** — Room DB, migrations 1→2 (added age), 2→3 (added food_cache + fromCache)
- **`data/db/Entities.kt`** — 4 tables:
  - `user_profile` — gender, age, weightKg, heightCm, goalsText
  - `daily_norms` — nutrientsJson (JSON string of NutrientData)
  - `food_entries` — date, foodName, weightGrams, nutrientsJson, source, fromCache
  - `food_cache` — keyOriginal, keyNormalized, keyEn, nutrientsPer100gJson (unique index on keyNormalized)
- **`data/db/Converters.kt`** — Type converters
- **DAOs**: `UserProfileDao`, `DailyNormsDao`, `FoodEntryDao`, `FoodCacheDao`

#### API Services
- **`data/api/ApiClient.kt`** — Retrofit client setup
- **`data/api/UsdaFdcApiService.kt`** — USDA FDC search endpoint (`/fdc/v1/foods/search`)
- **`data/api/UsdaFdcModels.kt`** — `UsdaSearchResponse`, `UsdaFood`, `UsdaFoodNutrient` (with nutrient ID constants)
  - Currently tracks: ENERGY(1008), PROTEIN(1003), FAT(1004), CARBS(1005), FIBER(1079), 13 vitamins, 11 minerals
  - **NOT tracked**: saturated fat (1258), monounsaturated (1292), polyunsaturated (1293), trans fat (1257), cholesterol (1253)
- **`data/api/OpenFoodFactsApiService.kt`** — OFF barcode lookup + search
- **`data/api/OpenFoodFactsModels.kt`** — `OFFNutriments` with per-100g and per-serving fields
  - **NOT tracked**: saturated fat, monounsaturated, polyunsaturated, trans fat, cholesterol (OFF has `saturated-fat_100g` etc.)
- **`data/api/OpenRouterApiService.kt`** — AI chat completion
- **`data/api/OpenRouterModels.kt`** — Request/response models
- **`data/api/GeminiApiService.kt`** / **`GeminiModels.kt`** — (legacy/unused?)

#### Repository
- **`data/repository/NutritionRepository.kt`** (1833 lines) — Main business logic:
  - AI models: textModels (gemini-2.5-flash-lite), visionModels (gemini-2.5-flash-lite), photoModels (gemini-2.5-flash), normsModels (gemini-2.5-pro-preview)
  - `calculateAndSaveNorms()` — AI calculates daily norms from user profile
  - `analyzeFoodText()` — Main food analysis pipeline:
    1. Local parse (split by comma, extract weight)
    2. Cache lookup (by normalized key or English key)
    3. AI identify (names RU + EN, weights)
    4. USDA search (with relevance scoring, data type priority, flour correction)
    5. Batch AI for items without USDA data
    6. Batch AI for missing micros on USDA items
    7. Fallback individual AI if all fails
    8. Cache results
  - `lookupBarcodeWithCache()` — OFF → AI enrich → cache
  - `lookupSupplementBarcode()` — OFF → AI per-serving nutrients → cache
  - `identifyAndAnalyzeFoodFromPhoto()` — Photo → AI (paid model) → nutrients per 100g
  - `analyzeSingleDish()` — AI nutrients for a single dish
  - `fillMissingMicrosWithAI()` — Fills zero micros with AI
  - `enrichNutrientsWithAI()` — Alias for fillMissing
  - USDA: flour enrichment correction (US fortification bias for Eastern Europe)
  - Sanity checks: macro calorie consistency, implausible macros rejection

#### Other Data
- **`data/NutrientTopFoods.kt`** — Static top-15 food sources per nutrient for info dialogs

### ViewModel Layer
- **`viewmodel/MainViewModel.kt`** — Main screen state (food input, dialogs, entries, norms, totals)
  - StateFlows: todayEntries, dailyNorms, hasProfile, userProfile, todayTotals, recentDates, cachedFoods
  - Actions: analyzeFood, confirmAddFood, deleteEntry, barcode/supplement/photo flows, profile update, cache management
- **`viewmodel/OnboardingViewModel.kt`** — Onboarding flow

### UI Layer

#### Screens (8 total)
- **`ui/screens/MainScreen.kt`** — Main: food input, today's entries table, macros/vitamins/minerals progress bars
- **`ui/screens/HistoryScreen.kt`** — Past days' food entries
- **`ui/screens/StatisticsScreen.kt`** — Charts/analytics over time
- **`ui/screens/OnboardingScreen.kt`** — Initial profile setup
- **`ui/screens/EditProfileScreen.kt`** — Edit profile
- **`ui/screens/BarcodeScannerScreen.kt`** — Camera barcode scanning
- **`ui/screens/PhotoCaptureScreen.kt`** — Camera food photo
- **`ui/screens/SavedProductsScreen.kt`** — Manage cached foods

#### Components
- **`ui/components/NutrientProgress.kt`** — Progress bars for nutrients:
  - `NutrientProgressBar` — Single bar with color coding (red/yellow/green/orange)
  - `MacrosProgressSection` — Card with cal, protein, fat, carbs, fiber bars
  - `VitaminsProgressSection` — Card with 13 vitamin bars
  - `MineralsProgressSection` — Card with 11 mineral bars
  - `SectionHeader` — Expandable section header
  - `NutrientTopFoodsDialog` — Shows top-15 foods for a nutrient
  - `NutrientBreakdownDialog` — Shows per-entry contribution to a nutrient
- **`ui/components/FoodTable.kt`** — Food entries table with edit mode:
  - Columns: Продукт, Вес, Ккал, Б, Ж, У
  - Edit mode: change weights, delete entries
  - Totals row
- **`ui/components/Dialogs.kt`** — Various dialogs:
  - `FoodConfirmationDialog` — Shows nutrients before adding
  - `EditWeightDialog` — Change portion weight
  - `BarcodeWeightDialog` — Enter weight for barcode product
  - `SupplementServingsDialog` — Enter servings for supplement
  - `PhotoEditDialog` — Edit food name/weight from photo

#### Navigation
- **`ui/navigation/Navigation.kt`** — Screen routes (sealed class)
- **`MainActivity.kt`** — NavHost setup
- **`NutritionApp.kt`** — Application class, repository init

#### Theme
- **`ui/theme/Color.kt`** — Colors (ProgressGreen, ProgressYellow, ProgressOrange, ProgressRed, ProgressBackground)
- **`ui/theme/Theme.kt`** — Material3 theme
- **`ui/theme/Type.kt`** — Typography

#### Utils
- **`util/Transliteration.kt`** — RU/UA → Latin transliteration for cache matching

---

## Adding a New Nutrient Field — Checklist

1. **`data/model/Models.kt`** — Add field to `NutrientData`:
   - Field declaration with `@SerializedName`
   - `operator plus` 
   - `operator times`
   - `macrosList()` or new list method (e.g. `fatDetailsList()`)
   - `getByKey()`
   - `withUpdatedKey()`
   - `allNutrientsList()`

2. **`data/api/UsdaFdcModels.kt`** — Add nutrient ID constant to `UsdaFoodNutrient.Companion`

3. **`data/api/OpenFoodFactsModels.kt`** — Add field to `OFFNutriments` (both `_100g` and `_serving`)

4. **`data/repository/NutritionRepository.kt`** — Multiple places:
   - `calculateAndSaveNorms()` — Add to AI prompt for daily norms
   - `analyzeFoodText()` Step 2 — Add to USDA `NutrientData(...)` construction (line ~597)
   - `analyzeFoodText()` Step 3 — Add to batch AI prompt and parsing
   - `analyzeFoodText()` Step 4 — Add to micro fill if applicable
   - `analyzeFoodText()` Step 5 — Add to fallback AI prompt/parsing
   - `analyzeSingleDish()` — Add to AI prompt and parsing
   - `identifyAndAnalyzeFoodFromPhoto()` — Add to AI prompt and parsing
   - `analyzeFoodPhoto()` — Add to AI prompt
   - `lookupBarcode()` — Add to OFF → NutrientData mapping
   - `fillMissingMicrosWithAI()` — Add check and copy
   - `getSupplementNutrientsFromAI()` — Add to AI prompt and parsing

5. **`ui/components/NutrientProgress.kt`** — Add to progress section (new section or expand macros)

6. **`ui/components/Dialogs.kt`** — `FoodConfirmationDialog` shows nutrients before adding

7. **`ui/components/FoodTable.kt`** — May need new columns if showing in table

8. **`data/NutrientTopFoods.kt`** — Add top-15 foods data for new nutrient

9. **`data/db/AppDatabase.kt`** — NO migration needed (nutrients stored as JSON string)

10. **`ui/screens/StatisticsScreen.kt`** — If tracking over time

---

## Data Flow

```
User Input → ViewModel.analyzeFood() → Repository.analyzeFoodText()
  → Local parse → Cache check → AI identify → USDA search → AI batch → Cache
  → FoodAnalysisResult returned to ViewModel
  → ViewModel shows confirmation dialog
  → User confirms → Repository.addFoodEntry() → Room DB
  → todayEntries Flow updates → UI recomposes
```

## Key Design Patterns
- Nutrients stored as JSON in Room (no migration needed for new nutrients)
- All nutrient values are per-portion in food_entries, per-100g in food_cache
- Cache uses normalized keys (lowercase, sorted words) + English key matching
- USDA results scored by: data type priority, word match, cooking terms, NFS/raw preference
- US flour enrichment corrected for Eastern European context
- AI used as fallback when USDA doesn't have data
- Barcode flow: OFF API → AI enriches missing micros → cache

## External APIs
- **USDA FDC** — Free, 1000 req/hr, search endpoint returns ~25 nutrients per food
- **OpenFoodFacts** — Free, barcode lookup, unreliable for supplements
- **OpenRouter** (→ Gemini models) — Paid, used for: identify food, get nutrients, photo analysis, daily norms, supplement info

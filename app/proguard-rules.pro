# ---- Retrofit / OkHttp / Gson (serialization uses reflection) ----
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses, EnclosingMethod

# Our network DTOs and domain models are (de)serialized by Gson via field/reflection —
# keep them wholesale so field names survive obfuscation.
-keep class com.nutrition.tracker.data.api.** { *; }
-keep class com.nutrition.tracker.data.model.** { *; }
# Room entities are persisted and their NutrientData columns are Gson-serialized.
-keep class com.nutrition.tracker.data.db.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Keep enum values()/valueOf() used by Gson.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Retrofit 2 ships consumer rules, but keep suspend-function service methods explicitly.
-keep,allowobfuscation interface com.nutrition.tracker.data.api.BackendApiService
-keep,allowobfuscation interface com.nutrition.tracker.data.api.OpenFoodFactsApiService
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ---- ZXing (QR generation) — referenced directly, no reflection, keep core just in case ----
-dontwarn com.google.zxing.**

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.nutrition.tracker.data.api.** { *; }
-keep class com.nutrition.tracker.data.model.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

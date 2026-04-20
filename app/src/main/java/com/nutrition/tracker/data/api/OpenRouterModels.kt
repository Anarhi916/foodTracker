package com.nutrition.tracker.data.api

import com.google.gson.annotations.SerializedName

// OpenRouter uses OpenAI-compatible chat completions format

data class OpenRouterRequest(
    val model: String = "google/gemma-3-12b-it:free",
    val messages: List<OpenRouterMessage>,
    val temperature: Float = 0.0f,
    @SerializedName("max_tokens")
    val maxTokens: Int = 4096
)

data class OpenRouterMessage(
    val role: String = "user",
    val content: Any // Can be String or List<OpenRouterContentPart>
)

data class OpenRouterContentPart(
    val type: String, // "text" or "image_url"
    val text: String? = null,
    @SerializedName("image_url")
    val imageUrl: OpenRouterImageUrl? = null
)

data class OpenRouterImageUrl(
    val url: String // "data:image/jpeg;base64,..."
)

data class OpenRouterResponse(
    val id: String? = null,
    val choices: List<OpenRouterChoice>? = null,
    val error: OpenRouterError? = null
)

data class OpenRouterChoice(
    val message: OpenRouterResponseMessage? = null
)

data class OpenRouterResponseMessage(
    val content: String? = null
)

data class OpenRouterError(
    val code: Int? = null,
    val message: String? = null
)
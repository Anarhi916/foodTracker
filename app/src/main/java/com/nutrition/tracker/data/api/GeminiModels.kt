package com.nutrition.tracker.data.api

import com.google.gson.annotations.SerializedName

data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig = GeminiGenerationConfig()
)

data class GeminiContent(
    val parts: List<GeminiPart>
)

data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null
)

data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

data class GeminiGenerationConfig(
    val temperature: Float = 0.15f,
    val responseMimeType: String = "application/json"
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null
)

data class GeminiCandidate(
    val content: GeminiCandidateContent? = null
)

data class GeminiCandidateContent(
    val parts: List<GeminiResponsePart>? = null
)

data class GeminiResponsePart(
    val text: String? = null
)

data class GeminiError(
    val code: Int? = null,
    val message: String? = null
)

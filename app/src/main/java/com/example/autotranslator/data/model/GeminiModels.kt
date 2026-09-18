package com.example.autotranslator.data.model

import com.google.gson.annotations.SerializedName

data class GeminiRequest(
    @SerializedName("contents") val contents: List<Content>,
    @SerializedName("systemInstruction") val systemInstruction: SystemInstruction? = null,
    @SerializedName("generationConfig") val generationConfig: GenerationConfig? = null,
)

data class Content(
    @SerializedName("parts") val parts: List<Part>
)

data class Part(
    @SerializedName("text") val text: String
)

data class SystemInstruction(
    @SerializedName("parts") val parts: List<Part>
)

data class GenerationConfig(
    @SerializedName("temperature") val temperature: Float = 0.3f
)

data class GeminiResponse(
    @SerializedName("candidates") val candidates: List<Candidate>?
)

data class Candidate(
    @SerializedName("content") val content: Content?
)

package com.example.autotranslator.data.model

import com.google.gson.annotations.SerializedName

data class GeminiRequest(
    @SerializedName("contents") val contents: List<Content>,
    @SerializedName("systemInstruction") val systemInstruction: Content? = null,
    @SerializedName("generationConfig") val generationConfig: GenerationConfig? = null,
)

data class Content(
    @SerializedName("parts") val parts: List<Part>
)

data class Part(
    @SerializedName("text") val text: String? = null,
    @SerializedName("inlineData") val inlineData: InlineData? = null
)

data class InlineData(
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("data") val data: String
)


data class GenerationConfig(
    @SerializedName("temperature") val temperature: Float = 0.3f,
    @SerializedName("responseMimeType") val responseMimeType: String? = null
)

data class GeminiResponse(
    @SerializedName("candidates") val candidates: List<Candidate>?
)

data class Candidate(
    @SerializedName("content") val content: Content?
)

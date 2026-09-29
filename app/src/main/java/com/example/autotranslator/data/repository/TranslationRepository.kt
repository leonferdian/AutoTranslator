package com.example.autotranslator.data.repository

import com.example.autotranslator.data.model.*
import com.example.autotranslator.data.network.GeminiApiService
import com.example.autotranslator.data.network.GoogleTranslateApiService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.firstOrNull
import java.io.IOException

enum class EngineType { GEMINI, GOOGLE }

interface TranslationEngine {
    suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String>
    suspend fun translateImage(base64Image: String, sourceLang: String, targetLang: String): Result<List<ImageTranslationResult>>
}

data class ImageTranslationResult(
    val translatedText: String,
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int
)

class GeminiTranslationEngine(
    private val apiService: GeminiApiService,
    private val settingsProvider: AppSettingsProvider
) : TranslationEngine {
    override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> {
        val apiKey = settingsProvider.getApiKey().firstOrNull() ?: ""
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Gemini API key missing"))

        val prompt = if (sourceLang == "Auto-Detect") {
            "Translate the provided text into $targetLang. "
        } else {
            "Translate the provided text from $sourceLang to $targetLang. "
        }

        val request = GeminiRequest(
            contents = listOf(Content(listOf(Part(text = text)))),
            systemInstruction = Content(
                parts = listOf(
                    Part(text = "You are a live translator. $prompt Do NOT add intros. Output ONLY raw translated text.")
                )
            ),
            generationConfig = GenerationConfig(temperature = 0.1f)
        )

        return try {
            val response = apiService.generateContent(apiKey, request)
            if (response.isSuccessful) {
                val candidateText = response.body()?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!candidateText.isNullOrBlank()) Result.success(candidateText.trim())
                else Result.failure(Exception("Empty Gemini response"))
            } else Result.failure(IOException("Gemini API Error ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun translateImage(base64Image: String, sourceLang: String, targetLang: String): Result<List<ImageTranslationResult>> {
        val apiKey = settingsProvider.getApiKey().firstOrNull() ?: ""
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Gemini API key missing"))

        val prompt = "Look at this image. Find all the $sourceLang text blocks in it. Translate them to $targetLang. " +
                "Return a JSON array where each object has 'translatedText' containing the translation, and 'top', 'left', 'bottom', 'right' integer values representing the bounding box coordinates of the original text in the image. " +
                "Only return the raw JSON array, nothing else."

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                    )
                )
            ),
            generationConfig = GenerationConfig(
                temperature = 0.1f,
                responseMimeType = "application/json"
            )
        )

        return try {
            val response = apiService.generateContent(apiKey, request)
            if (response.isSuccessful) {
                val candidateText = response.body()?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!candidateText.isNullOrBlank()) {
                    try {
                        // Gemini often returns JSON wrapped in markdown code blocks even with responseMimeType="application/json"
                        val jsonText = candidateText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                        val type = object : TypeToken<List<ImageTranslationResult>>() {}.type
                        val results: List<ImageTranslationResult> = Gson().fromJson(jsonText, type)
                        Result.success(results)
                    } catch (e: Exception) {
                        Result.failure(Exception("Failed to parse Gemini JSON: $candidateText", e))
                    }
                } else Result.failure(Exception("Empty Gemini response"))
            } else Result.failure(IOException("Gemini API Error ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class GoogleTranslationEngine(
    private val apiService: GoogleTranslateApiService,
    private val settingsProvider: AppSettingsProvider
) : TranslationEngine {
    override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> {
        val apiKey = settingsProvider.getGoogleApiKey().firstOrNull() ?: ""
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Google Translate API key missing"))

        val sourceMap = mapOf("English" to "en", "Thai (ไทย)" to "th", "Indonesian (Bahasa)" to "id", "Japanese (日本語)" to "ja", "Chinese (中文)" to "zh-CN", "Korean (한국어)" to "ko")
        val sourceCode = if (sourceLang == "Auto-Detect") "" else sourceMap[sourceLang] ?: "en"
        val targetCode = sourceMap[targetLang] ?: "th"

        return try {
            val response = apiService.translateText(apiKey, text, sourceCode, targetCode)
            if (response.isSuccessful) {
                val translatedText = response.body()?.data?.translations?.firstOrNull()?.translatedText
                if (!translatedText.isNullOrBlank()) Result.success(translatedText)
                else Result.failure(Exception("Empty Google Translate response"))
            } else Result.failure(IOException("Google API Error ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun translateImage(base64Image: String, sourceLang: String, targetLang: String): Result<List<ImageTranslationResult>> {
        return Result.failure(UnsupportedOperationException("Google Translate API does not support direct image translation in this app. Use Gemini."))
    }
}

interface TranslationRepository {
    suspend fun translateText(text: String): Result<String>
    suspend fun translateImage(base64Image: String): Result<List<ImageTranslationResult>>
}

class TranslationRepositoryImpl(
    private val geminiEngine: GeminiTranslationEngine,
    private val googleEngine: GoogleTranslationEngine,
    private val settingsProvider: AppSettingsProvider,
) : TranslationRepository {

    override suspend fun translateText(text: String): Result<String> {
        val engineType = settingsProvider.getTranslationEngine().firstOrNull() ?: "Gemini"
        val sourceLang = settingsProvider.getSourceLanguage().firstOrNull() ?: "Auto-Detect"
        val targetLang = settingsProvider.getTargetLanguage().firstOrNull() ?: "Thai (ไทย)"
        val fallbackEnabled = settingsProvider.getFallbackEnabled().firstOrNull() ?: false

        if (engineType == "Gemini") {
            val result = geminiEngine.translate(text, sourceLang, targetLang)
            if (result.isFailure && fallbackEnabled) {
                return googleEngine.translate(text, sourceLang, targetLang)
            }
            return result
        }
        
        return googleEngine.translate(text, sourceLang, targetLang)
    }

    override suspend fun translateImage(base64Image: String): Result<List<ImageTranslationResult>> {
        val sourceLang = settingsProvider.getSourceLanguage().firstOrNull() ?: "Auto-Detect"
        val targetLang = settingsProvider.getTargetLanguage().firstOrNull() ?: "Thai (ไทย)"
        
        // Always route image translation to Gemini since Google Translate REST doesn't support it
        return geminiEngine.translateImage(base64Image, sourceLang, targetLang)
    }
}

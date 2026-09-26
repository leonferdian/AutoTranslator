package com.example.autotranslator.data.repository

import com.example.autotranslator.data.model.*
import com.example.autotranslator.data.network.GeminiApiService
import kotlinx.coroutines.flow.firstOrNull
import java.io.IOException

enum class EngineType { GEMINI, GOOGLE }

interface TranslationEngine {
    suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String>
}

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
            systemInstruction = SystemInstruction(
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
}

class GoogleTranslationEngine(
    private val settingsProvider: AppSettingsProvider
) : TranslationEngine {
    override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> {
        // Implementation for Google Cloud Translation REST API
        // For now, simulating for the refactor structure
        return Result.success("[Google] $text")
    }
}

interface TranslationRepository {
    suspend fun translateText(text: String): Result<String>
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
}

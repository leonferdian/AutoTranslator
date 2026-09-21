package com.example.autotranslator.data.repository

import com.example.autotranslator.data.model.*
import com.example.autotranslator.data.network.GeminiApiService
import kotlinx.coroutines.flow.firstOrNull
import java.io.IOException

interface TranslationRepository {
    suspend fun translateText(text: String): Result<String>
}

class TranslationRepositoryImpl(
    private val apiService: GeminiApiService,
    private val settingsProvider: AppSettingsProvider,
) : TranslationRepository {

    override suspend fun translateText(text: String): Result<String> {
        val engine = settingsProvider.getTranslationEngine().firstOrNull() ?: "Gemini"
        
        return if (engine == "Gemini") {
            translateWithGemini(text)
        } else {
            translateWithGoogle(text)
        }
    }

    private suspend fun translateWithGemini(text: String): Result<String> {
        val apiKey = settingsProvider.getApiKey().firstOrNull() ?: ""
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Gemini API key is not configured."))
        }

        val sourceLang = settingsProvider.getSourceLanguage().firstOrNull() ?: "Auto-Detect"
        val targetLang = settingsProvider.getTargetLanguage().firstOrNull() ?: "Thai (ไทย)"
        
        val prompt = if (sourceLang == "Auto-Detect") {
            "Translate the provided text into $targetLang. "
        } else {
            "Translate the provided text from $sourceLang to $targetLang. "
        }

        val request = GeminiRequest(
            contents = listOf(Content(listOf(Part(text = text)))),
            systemInstruction = SystemInstruction(
                parts = listOf(
                    Part(
                        text = "You are a live, ultra-fast translator. $prompt" +
                               "Maintain chat/gaming slang, emotions, and shorthand terms contextually. " +
                               "Do NOT add any introductions, explanations, or metadata. Output ONLY the raw translated text."
                    )
                )
            ),
            generationConfig = GenerationConfig(temperature = 0.2f)
        )

        return try {
            val response = apiService.generateContent(apiKey, request)
            if (response.isSuccessful) {
                val candidateText = response.body()?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!candidateText.isNullOrBlank()) {
                    Result.success(candidateText.trim())
                } else {
                    Result.failure(Exception("Empty translation response from Gemini API."))
                }
            } else {
                Result.failure(IOException("API Error ${response.code()}: ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun translateWithGoogle(text: String): Result<String> {
        // For now, let's use a public endpoint or just simulate it for the demo
        // Real implementation would use Google Cloud Translation API
        return Result.success("[Google Translate] $text (Mocked)")
    }
}

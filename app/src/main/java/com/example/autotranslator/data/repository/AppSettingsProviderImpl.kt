package com.example.autotranslator.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppSettingsProviderImpl(private val context: Context) : AppSettingsProvider {

    companion object {
        private val API_KEY = stringPreferencesKey("api_key")
        private val TARGET_LANGUAGE = stringPreferencesKey("target_language")
        private val SOURCE_LANGUAGE = stringPreferencesKey("source_language")
        private val TRANSLATION_ENGINE = stringPreferencesKey("translation_engine")
        private val FALLBACK_ENABLED = booleanPreferencesKey("fallback_enabled")
        private val GOOGLE_API_KEY = stringPreferencesKey("google_api_key")
        private val BUBBLE_SIZE = intPreferencesKey("bubble_size")
        private val OPACITY = intPreferencesKey("opacity")
        private val FONT_SCALE = intPreferencesKey("font_scale")
    }

    override fun getApiKey(): Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }
    override suspend fun setApiKey(apiKey: String) {
        context.dataStore.edit { it[API_KEY] = apiKey }
    }

    override fun getTargetLanguage(): Flow<String> = context.dataStore.data.map { it[TARGET_LANGUAGE] ?: "Thai (ไทย)" }
    override suspend fun setTargetLanguage(language: String) {
        context.dataStore.edit { it[TARGET_LANGUAGE] = language }
    }

    override fun getSourceLanguage(): Flow<String> = context.dataStore.data.map { it[SOURCE_LANGUAGE] ?: "Auto-Detect" }
    override suspend fun setSourceLanguage(language: String) {
        context.dataStore.edit { it[SOURCE_LANGUAGE] = language }
    }

    override fun getTranslationEngine(): Flow<String> = context.dataStore.data.map { it[TRANSLATION_ENGINE] ?: "Gemini" }
    override suspend fun setTranslationEngine(engine: String) {
        context.dataStore.edit { it[TRANSLATION_ENGINE] = engine }
    }

    override fun getFallbackEnabled(): Flow<Boolean> = context.dataStore.data.map { it[FALLBACK_ENABLED] ?: false }
    override suspend fun setFallbackEnabled(enabled: Boolean) {
        context.dataStore.edit { it[FALLBACK_ENABLED] = enabled }
    }

    override fun getGoogleApiKey(): Flow<String> = context.dataStore.data.map { it[GOOGLE_API_KEY] ?: "" }
    override suspend fun setGoogleApiKey(apiKey: String) {
        context.dataStore.edit { it[GOOGLE_API_KEY] = apiKey }
    }

    override fun getBubbleSize(): Flow<Int> = context.dataStore.data.map { it[BUBBLE_SIZE] ?: 64 }
    override suspend fun setBubbleSize(size: Int) {
        context.dataStore.edit { it[BUBBLE_SIZE] = size }
    }

    override fun getOpacity(): Flow<Int> = context.dataStore.data.map { it[OPACITY] ?: 85 }
    override suspend fun setOpacity(opacity: Int) {
        context.dataStore.edit { it[OPACITY] = opacity }
    }

    override fun getFontScale(): Flow<Int> = context.dataStore.data.map { it[FONT_SCALE] ?: 16 }
    override suspend fun setFontScale(scale: Int) {
        context.dataStore.edit { it[FONT_SCALE] = scale }
    }
}

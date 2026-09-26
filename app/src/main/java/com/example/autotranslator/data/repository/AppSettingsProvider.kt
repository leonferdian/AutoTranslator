package com.example.autotranslator.data.repository

import kotlinx.coroutines.flow.Flow

interface AppSettingsProvider {
    fun getApiKey(): Flow<String>
    suspend fun setApiKey(apiKey: String)
    
    fun getTargetLanguage(): Flow<String>
    suspend fun setTargetLanguage(language: String)

    fun getSourceLanguage(): Flow<String>
    suspend fun setSourceLanguage(language: String)

    fun getTranslationEngine(): Flow<String>
    suspend fun setTranslationEngine(engine: String)

    fun getFallbackEnabled(): Flow<Boolean>
    suspend fun setFallbackEnabled(enabled: Boolean)

    fun getGoogleApiKey(): Flow<String>
    suspend fun setGoogleApiKey(apiKey: String)

    fun getBubbleSize(): Flow<Int>
    suspend fun setBubbleSize(size: Int)

    fun getOpacity(): Flow<Int>
    suspend fun setOpacity(opacity: Int)

    fun getFontScale(): Flow<Int>
    suspend fun setFontScale(scale: Int)

    fun getDynamicModeEnabled(): Flow<Boolean>
    suspend fun setDynamicModeEnabled(enabled: Boolean)
}

package com.example.autotranslator.data

import android.content.Context
import com.example.autotranslator.data.network.GeminiApiService
import com.example.autotranslator.data.repository.AppSettingsProvider
import com.example.autotranslator.data.repository.AppSettingsProviderImpl
import com.example.autotranslator.data.repository.TranslationRepository
import com.example.autotranslator.data.repository.TranslationRepositoryImpl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ServiceLocator {
    private var appSettingsProvider: AppSettingsProvider? = null
    private var geminiApiService: GeminiApiService? = null
    private var translationRepository: TranslationRepository? = null

    fun provideAppSettings(context: Context): AppSettingsProvider {
        return appSettingsProvider ?: synchronized(this) {
            val instance = AppSettingsProviderImpl(context.applicationContext)
            appSettingsProvider = instance
            instance
        }
    }

    private fun provideGeminiApi(): GeminiApiService {
        return geminiApiService ?: synchronized(this) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl("https://generativelanguage.googleapis.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build()

            val instance = retrofit.create(GeminiApiService::class.java)
            geminiApiService = instance
            instance
        }
    }

    fun provideTranslationRepository(context: Context): TranslationRepository {
        return translationRepository ?: synchronized(this) {
            val instance = TranslationRepositoryImpl(
                provideGeminiApi(),
                provideAppSettings(context)
            )
            translationRepository = instance
            instance
        }
    }
}

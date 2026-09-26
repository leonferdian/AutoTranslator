package com.example.autotranslator.data.network

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

data class GoogleTranslateResponse(val data: GoogleTranslateData?)
data class GoogleTranslateData(val translations: List<GoogleTranslation>?)
data class GoogleTranslation(val translatedText: String?)

interface GoogleTranslateApiService {
    @GET("language/translate/v2")
    suspend fun translateText(
        @Query("key") apiKey: String,
        @Query("q") query: String,
        @Query("source") sourceLanguage: String,
        @Query("target") targetLanguage: String,
        @Query("format") format: String = "text"
    ): Response<GoogleTranslateResponse>
}

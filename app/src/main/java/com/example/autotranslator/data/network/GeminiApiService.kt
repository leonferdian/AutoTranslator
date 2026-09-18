package com.example.autotranslator.data.network

import com.example.autotranslator.data.model.GeminiRequest
import com.example.autotranslator.data.model.GeminiResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface GeminiApiService {
    @POST("v1beta/models/gemini-1.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest,
    ): Response<GeminiResponse>
}

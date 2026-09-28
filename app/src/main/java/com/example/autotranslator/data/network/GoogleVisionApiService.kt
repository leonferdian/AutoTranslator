package com.example.autotranslator.data.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

data class VisionRequest(val requests: List<AnnotateImageRequest>)
data class AnnotateImageRequest(val image: VisionImage, val features: List<VisionFeature>)
data class VisionImage(val content: String)
data class VisionFeature(val type: String = "TEXT_DETECTION")

data class VisionResponse(val responses: List<AnnotateImageResponse>?)
data class AnnotateImageResponse(val textAnnotations: List<TextAnnotation>?)
data class TextAnnotation(val description: String?, val boundingPoly: BoundingPoly?)
data class BoundingPoly(val vertices: List<Vertex>?)
data class Vertex(val x: Int?, val y: Int?)

interface GoogleVisionApiService {
    @POST("v1/images:annotate")
    suspend fun annotateImage(
        @Query("key") apiKey: String,
        @Body request: VisionRequest
    ): Response<VisionResponse>
}

package com.icps.credentialverifier

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

data class CredentialResponseDto(
    val id: String?,
    val chip_uid: String?,
    val first_name: String,
    val last_name: String,
    val course: String,
    val university: String,
    val duration: String,
    val `class`: String,
    val has_photo: Boolean = false
)

interface CredentialApi {
    @GET("credentials/by-qr/{qrToken}")
    suspend fun getByQrToken(@Path("qrToken") qrToken: String): Response<CredentialResponseDto>

    @GET("credentials/by-chip/{chipUid}")
    suspend fun getByChipUid(@Path("chipUid") chipUid: String): Response<CredentialResponseDto>

    @GET("credentials/{id}/photo")
    suspend fun getPhoto(@Path("id") id: String): Response<ResponseBody>
}

object ApiClient {
    val credentialApi: CredentialApi by lazy {
        Retrofit.Builder()
            .baseUrl(normalizedBaseUrl())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CredentialApi::class.java)
    }

    private fun normalizedBaseUrl(): String {
        return BuildConfig.API_BASE_URL.trim().let { value ->
            if (value.endsWith("/")) value else "$value/"
        }
    }
}

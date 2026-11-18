package com.shelfscan.app.data.remote

import com.shelfscan.app.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

@Serializable
data class BookDto(
    val title: String,
    val author: String,
    val genres: List<String> = emptyList(),
    val isbn: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val description: String? = null,
    @SerialName("published_date") val publishedDate: String? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    val confidence: Double = 1.0,
)

@Serializable data class IdentifyResponse(val books: List<BookDto>, val notes: String? = null)
@Serializable data class OwnedBookDto(val title: String, val author: String, val genres: List<String>)
@Serializable data class RecommendRequest(
    val books: List<OwnedBookDto>,
    val limit: Int = 10,
    val author: String? = null,
    val genre: String? = null,
    @SerialName("similar_to") val similarTo: OwnedBookDto? = null,
)

@Serializable data class RecommendationDto(
    val book: BookDto,
    val reason: String = "",
    /** "by_author": more by the focused author; "similar": by other authors. */
    val section: String = SECTION_SIMILAR,
) {
    companion object {
        const val SECTION_BY_AUTHOR = "by_author"
        const val SECTION_SIMILAR = "similar"
    }
}
@Serializable data class RecommendResponse(val recommendations: List<RecommendationDto>)
@Serializable data class UpcomingRequest(val authors: List<String>)
@Serializable data class BookRefDto(val title: String, val author: String, val isbn: String? = null)
@Serializable data class CoversRequest(val books: List<BookRefDto>)
@Serializable data class CoverDto(@SerialName("cover_url") val coverUrl: String? = null, val isbn: String? = null)
@Serializable data class CoversResponse(val covers: List<CoverDto>)
@Serializable data class DetailsRequest(
    val title: String,
    val author: String,
    val isbn: String? = null,
    val description: String? = null,
)
@Serializable data class DetailsResponse(
    @SerialName("cover_url") val coverUrl: String? = null,
    val isbn: String? = null,
    val description: String? = null,
    val synopsis: String? = null,
    @SerialName("published_date") val publishedDate: String? = null,
    @SerialName("page_count") val pageCount: Int? = null,
)
@Serializable data class UpcomingResponse(val books: List<BookDto>)
@Serializable data class CandidatesResponse(val candidates: List<String> = emptyList())
@Serializable data class IsbnRequest(val isbn: String)
@Serializable data class SearchRequest(val query: String, val limit: Int = 15)
@Serializable data class SearchResponse(val books: List<BookDto> = emptyList())
@Serializable data class IsbnResponse(val book: BookDto? = null)
@Serializable data class SeriesRequest(val books: List<OwnedBookDto>, val refresh: Boolean = false)
@Serializable data class SeriesVolumeDto(
    val number: Int,
    val title: String,
    val owned: Boolean,
    /** A missing volume: the catalog's record (cover, date). Null for the ones you own. */
    val book: BookDto? = null,
)
/** A series you own part of: its owned and missing published volumes, in reading order. */
@Serializable data class SeriesGapDto(val name: String, val author: String, val volumes: List<SeriesVolumeDto>)
@Serializable data class SeriesResponse(val series: List<SeriesGapDto> = emptyList())

interface ShelfApi {
    @Multipart
    @POST("identify")
    suspend fun identify(@Part images: List<MultipartBody.Part>): IdentifyResponse

    @POST("recommendations")
    suspend fun recommendations(@Body body: RecommendRequest): RecommendResponse

    @POST("upcoming")
    suspend fun upcoming(@Body body: UpcomingRequest): UpcomingResponse

    @POST("covers")
    suspend fun covers(@Body body: CoversRequest): CoversResponse

    @POST("book/details")
    suspend fun bookDetails(@Body body: DetailsRequest): DetailsResponse

    @POST("covers/candidates")
    suspend fun coverCandidates(@Body body: BookRefDto): CandidatesResponse

    @POST("search")
    suspend fun search(@Body body: SearchRequest): SearchResponse

    @POST("lookup/isbn")
    suspend fun lookupIsbn(@Body body: IsbnRequest): IsbnResponse

    @POST("series/gaps")
    suspend fun seriesGaps(@Body body: SeriesRequest): SeriesResponse

    companion object {
        fun create(): ShelfApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // Local vision models can take minutes on several photos. Keep this above the
                // server's LLM_TIMEOUT so the server's own error reaches the app instead of a bare timeout.
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                    if (BuildConfig.APP_TOKEN.isNotEmpty()) req.header("X-App-Token", BuildConfig.APP_TOKEN)
                    chain.proceed(req.build())
                }
                .build()
            val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
            return Retrofit.Builder()
                .baseUrl(BuildConfig.SERVER_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ShelfApi::class.java)
        }
    }
}

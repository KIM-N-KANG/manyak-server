package com.knk.manyak.story.client

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI

interface StoryAiClient {
    fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse
}

data class AiStorylinesRequest(
    val genre_tags: List<String>,

    val protagonist_tags: List<String>,

    val supporting_tags: List<String>,
)

data class AiStorylinesResponse(
    val stories: List<AiStoryItem>,
)

data class AiStoryItem(
    val id: Int,
    val story: String,
    val questions: List<String>,
)

@Component
class RestStoryAiClient(
    @Value("\${manyak.ai.base-url}") aiBaseUrl: String,
) : StoryAiClient {
    private val validatedAiBaseUrl = validateAiBaseUrl(aiBaseUrl)
    private val restClient = RestClient
        .builder()
        .baseUrl(validatedAiBaseUrl.toString())
        .requestFactory(SimpleClientHttpRequestFactory())
        .build()

    override fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse =
        restClient
            .post()
            .uri("/api/v1/story/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(AiStorylinesResponse::class.java)
            ?: throw IllegalStateException("AI storylines response body is empty")

    private fun validateAiBaseUrl(aiBaseUrl: String): URI {
        val uri = URI.create(aiBaseUrl.trim())
        require(uri.scheme == "http" || uri.scheme == "https") {
            "manyak.ai.base-url must include http or https scheme. example: http://localhost:8000"
        }
        require(uri.host != null) {
            "manyak.ai.base-url must include host. example: http://localhost:8000"
        }
        return uri
    }
}

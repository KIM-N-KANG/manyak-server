package com.knk.manyak.story.client

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Duration

interface StoryAiClient {
    fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse

    fun compileStory(request: AiStoryCompileRequest): AiStoryCompileResponse
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

data class AiStoryCompileRequest(
    val genre_tags: List<String>,

    val protagonist_tags: List<String>,

    val supporting_tags: List<String>,

    val selected_storyline: String,

    val extra_info: String,
)

data class AiStoryCompileResponse(
    val stories: AiStoryMeta,

    val story_settings: AiStorySettings,

    val story_start_settings: AiStoryStartSettings,

    val story_suggested_inputs: List<String>,
)

data class AiStoryMeta(
    val title: String,
    val one_line_intro: String,
    val description: String,
)

data class AiStorySettings(
    val world_setting: String,
    val character_setting: String,
    val user_role_setting: String,
    val rule_setting: String,
)

data class AiStoryStartSettings(
    val name: String,
    val start_situation: String,
    val prologue: String,
)

@Component
class RestStoryAiClient(
    @Value("\${manyak.ai.base-url}") aiBaseUrl: String,
    connectTimeout: Duration = Duration.ofSeconds(5),
    readTimeout: Duration = Duration.ofSeconds(15),
) : StoryAiClient {
    private val validatedAiBaseUrl = validateAiBaseUrl(aiBaseUrl)
    private val restClient = RestClient
        .builder()
        .baseUrl(validatedAiBaseUrl.toString())
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(connectTimeout)
                setReadTimeout(readTimeout)
            },
        )
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

    override fun compileStory(request: AiStoryCompileRequest): AiStoryCompileResponse =
        restClient
            .post()
            .uri("/api/v1/story/compile")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(AiStoryCompileResponse::class.java)
            ?: throw IllegalStateException("AI story compile response body is empty")

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

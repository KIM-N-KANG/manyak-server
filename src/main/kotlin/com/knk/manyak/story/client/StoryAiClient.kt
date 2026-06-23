package com.knk.manyak.story.client

import com.fasterxml.jackson.annotation.JsonProperty
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
    @JsonProperty("genre_tags")
    val genreTags: List<String>,

    @JsonProperty("protagonist_tags")
    val protagonistTags: List<String>,

    @JsonProperty("supporting_tags")
    val supportingTags: List<String>,
)

data class AiStorylinesResponse(
    val stories: List<AiStoryItem>,
)

data class AiStoryItem(
    val id: Int,
    val story: String,
    @JsonProperty("recommended_infos")
    val recommendedInfos: List<String>,
)

data class AiStoryCompileRequest(
    @JsonProperty("genre_tags")
    val genreTags: List<String>,

    @JsonProperty("protagonist_tags")
    val protagonistTags: List<String>,

    @JsonProperty("supporting_tags")
    val supportingTags: List<String>,

    @JsonProperty("selected_storyline")
    val selectedStoryline: String,

    @JsonProperty("extra_info")
    val extraInfo: String,
)

data class AiStoryCompileResponse(
    val stories: AiStoryMeta,

    @JsonProperty("story_settings")
    val storySettings: AiStorySettings,

    @JsonProperty("story_start_settings")
    val storyStartSettings: AiStoryStartSettings,

    @JsonProperty("story_suggested_inputs")
    val storySuggestedInputs: List<String>,
)

data class AiStoryMeta(
    val title: String,
    @JsonProperty("one_line_intro")
    val oneLineIntro: String,
    val description: String,
)

data class AiStorySettings(
    @JsonProperty("world_setting")
    val worldSetting: String,
    @JsonProperty("character_setting")
    val characterSetting: String,
    @JsonProperty("user_role_setting")
    val userRoleSetting: String,
    @JsonProperty("rule_setting")
    val ruleSetting: String,
)

data class AiStoryStartSettings(
    val name: String,
    @JsonProperty("start_situation")
    val startSituation: String,
    val prologue: String,
)

@Component
class RestStoryAiClient(
    @Value("\${manyak.ai.base-url}") aiBaseUrl: String,
    connectTimeout: Duration = Duration.ofSeconds(5),
    storylineReadTimeout: Duration = Duration.ofSeconds(30),
    compileReadTimeout: Duration = Duration.ofSeconds(120),
) : StoryAiClient {
    private val validatedAiBaseUrl = validateAiBaseUrl(aiBaseUrl)

    // storyline 생성과 compile은 응답 시간 특성이 달라 read timeout을 분리한다.
    private val storylineRestClient = buildRestClient(connectTimeout, storylineReadTimeout)
    private val compileRestClient = buildRestClient(connectTimeout, compileReadTimeout)

    override fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse =
        storylineRestClient
            .post()
            .uri("/api/v1/story/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(AiStorylinesResponse::class.java)
            ?: throw IllegalStateException("AI storylines response body is empty")

    override fun compileStory(request: AiStoryCompileRequest): AiStoryCompileResponse =
        compileRestClient
            .post()
            .uri("/api/v1/story/compile")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(AiStoryCompileResponse::class.java)
            ?: throw IllegalStateException("AI story compile response body is empty")

    private fun buildRestClient(connectTimeout: Duration, readTimeout: Duration): RestClient =
        RestClient
            .builder()
            .baseUrl(validatedAiBaseUrl.toString())
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(connectTimeout)
                    setReadTimeout(readTimeout)
                },
            )
            .build()

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

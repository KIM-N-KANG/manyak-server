package com.knk.manyak.story.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.global.observability.CorrelationHeaders
import com.knk.manyak.global.observability.aicall.AiCallMeta
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestInterceptor
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class AiStorylinesResponse(
    val stories: List<AiStoryItem>,
    val meta: AiResponseMeta? = null,
)

/**
 * AI story 응답(storyline/compile)에 실려 오는 호출 meta. 와이어 키는 snake_case다.
 *
 * 미지 필드를 무시해, AI가 meta를 확장하거나 strict 역직렬화가 켜져도 파싱이 깨지지 않게 한다
 * (Jackson 기본값 FAIL_ON_UNKNOWN_PROPERTIES=false에만 의존하지 않는다).
 * promptVersions는 AI가 보낸 키→버전 맵(예: {"STORYLINES":2}, {"COMPILE":2})을 그대로 받는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiResponseMeta(
    val model: String? = null,
    val provider: String? = null,
    @JsonProperty("input_token_count")
    val inputTokenCount: Int? = null,
    @JsonProperty("output_token_count")
    val outputTokenCount: Int? = null,
    @JsonProperty("retry_count")
    val retryCount: Int? = null,
    @JsonProperty("prompt_versions")
    val promptVersions: Map<String, Int>? = null,
) {
    /** ai_call_logs 적재용 공통 도메인 meta로 정규화한다. */
    fun toAiCallMeta(): AiCallMeta = AiCallMeta(
        model = model,
        provider = provider,
        inputTokenCount = inputTokenCount,
        outputTokenCount = outputTokenCount,
        retryCount = retryCount,
        promptVersions = promptVersions,
    )
}

data class AiStoryItem(
    val id: Int,
    val storyline: String,
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

    @JsonProperty("additional_info")
    val additionalInfo: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AiStoryCompileResponse(
    val stories: AiStoryMeta,

    @JsonProperty("story_settings")
    val storySettings: AiStorySettings,

    @JsonProperty("story_start_settings")
    val storyStartSettings: AiStoryStartSettings,

    @JsonProperty("story_suggested_inputs")
    val storySuggestedInputs: List<String>,

    val meta: AiResponseMeta? = null,
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
            .requestInterceptor(correlationForwardingInterceptor())
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(connectTimeout)
                    setReadTimeout(readTimeout)
                },
            )
            .build()

    /**
     * 동기 RestClient는 호출 스레드에서 실행되므로 인터셉터 시점에 MDC가 살아 있다.
     * storyline·compile 양쪽 RestClient에 동일하게 적용해 상관관계 헤더를 forward한다.
     */
    private fun correlationForwardingInterceptor() = ClientHttpRequestInterceptor { request, body, execution ->
        CorrelationHeaders.forwardingHeadersFromMdc().forEach { (name, value) -> request.headers.set(name, value) }
        execution.execute(request, body)
    }

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

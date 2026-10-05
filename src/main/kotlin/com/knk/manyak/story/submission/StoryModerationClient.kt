package com.knk.manyak.story.submission

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.global.observability.CorrelationHeaders
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.net.http.HttpClient
import java.time.Duration

@JsonIgnoreProperties(ignoreUnknown = true)
data class ModerationImageError(val path: String, @JsonProperty("error_code") val errorCode: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ModerationResult(
    val decision: String,
    val issues: List<ModerationIssue>,
    @JsonProperty("error_code") val errorCode: String?,
    @JsonProperty("image_errors") val imageErrors: List<ModerationImageError> = emptyList(),
) {
    fun validated(input: JsonNode): ModerationResult = try {
        validate(input)
    } catch (_: IllegalArgumentException) {
        // 응답 자체는 채택하지 않더라도 사용자 수정 사유를 일시 장애로 바꾸어 반복하지 않는다.
        val permanentImageCodes = setOf("IMAGE_INVALID", "IMAGE_UNREADABLE")
        throw InvalidModerationResponse(issues.isEmpty() && errorCode !in permanentImageCodes &&
            imageErrors.none { it.errorCode in permanentImageCodes })
    }

    private fun validate(input: JsonNode): ModerationResult {
        require(decision in setOf("APPROVED", "REJECTED"))
        require(errorCode == null || errorCode in IMAGE_ERROR_PRIORITY || errorCode == "MODEL_CALL_FAILED")
        require(when {
            decision == "APPROVED" -> issues.isEmpty() && errorCode == null && imageErrors.isEmpty()
            imageErrors.isNotEmpty() -> errorCode == IMAGE_ERROR_PRIORITY.firstOrNull { code -> imageErrors.any { it.errorCode == code } }
            errorCode == "MODEL_CALL_FAILED" -> issues.isEmpty()
            else -> errorCode == null && issues.isNotEmpty()
        })
        val paths = mutableMapOf<String, String>()
        fun collect(node: JsonNode, path: String) {
            when {
                node.isObject -> node.properties().forEach { (key, value) ->
                    if (path.isNotEmpty() || key !in setOf("submissionId", "storyId")) collect(value, if (path.isEmpty()) key else "$path.$key")
                }
                node.isArray -> node.forEachIndexed { index, value -> collect(value, "$path[$index]") }
                node.isString -> paths[path] = if (path == "thumbnailUrl" || CHARACTER_IMAGE_PATH.matches(path)) "IMAGE" else "TEXT"
            }
        }
        collect(input, "")
        require(issues.all { paths[it.path] == it.type && it.rule in RULES && it.reason.isNotBlank() })
        require(imageErrors.all { paths[it.path] == "IMAGE" && it.errorCode in IMAGE_ERROR_PRIORITY })
        require(imageErrors.map { it.path }.distinct().size == imageErrors.size)
        return this
    }

    companion object {
        private val IMAGE_ERROR_PRIORITY = listOf("IMAGE_INVALID", "IMAGE_UNREADABLE", "IMAGE_DOWNLOAD_FAILED")
        private val CHARACTER_IMAGE_PATH = Regex("""characters\[\d+]\.images\[\d+]\.imageUrl""")
        // AI Spec §5-3-6의 응답 코드 계약. 내용 판정 정책은 AI가 소유한다.
        private val RULES = setOf("MINOR_SEXUAL_EXPLOITATION", "EXPLICIT_SEXUAL_CONTENT",
            "NONCONSENSUAL_SEXUAL_EXPLOITATION", "DRUGS", "EXTREME_GORE",
            "SELF_HARM_PROMOTION", "HATE_VIOLENCE_INCITEMENT")
    }
}
class InvalidModerationResponse(val retryAllowed: Boolean) : IllegalArgumentException("Invalid moderation response")

fun interface StoryModerationClient { fun moderate(input: JsonNode): ModerationResult }

@Component
@ConditionalOnProperty(name = ["manyak.ai.moderation.stub"], havingValue = "true")
class StubStoryModerationClient : StoryModerationClient {
    override fun moderate(input: JsonNode) = ModerationResult("APPROVED", emptyList(), null)
}

@Component
@ConditionalOnProperty(name = ["manyak.ai.moderation.stub"], havingValue = "false", matchIfMissing = true)
class RestStoryModerationClient(
    @Value("\${manyak.ai.base-url}") baseUrl: String,
    @Value("\${manyak.ai.moderation.timeout:180s}") timeout: Duration,
) : StoryModerationClient {
    init { require(timeout > Duration.ofSeconds(150)) { "Moderation timeout must exceed 150 seconds" } }
    private val client = RestClient.builder().baseUrl(baseUrl)
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()).apply { setReadTimeout(timeout) }).build()
    override fun moderate(input: JsonNode): ModerationResult = client.post().uri("/api/v1/moderation/story")
        .headers { headers -> CorrelationHeaders.forwardingHeadersFromMdc().forEach { (name, value) -> headers.set(name, value) } }
        .body(input).retrieve().body(ModerationResult::class.java)?.validated(input) ?: error("Empty moderation response")
}

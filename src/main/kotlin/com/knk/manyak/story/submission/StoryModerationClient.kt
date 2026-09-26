package com.knk.manyak.story.submission

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

data class ModerationResult(val decision: String, val issues: List<ModerationIssue>, @JsonProperty("error_code") val errorCode: String?) {
    fun validated(): ModerationResult {
        require(decision in setOf("APPROVED", "REJECTED"))
        require(errorCode == null || errorCode in setOf("IMAGE_READ_FAILED", "MODEL_CALL_FAILED"))
        require(when {
            decision == "APPROVED" -> issues.isEmpty() && errorCode == null
            errorCode != null -> issues.isEmpty()
            else -> issues.isNotEmpty()
        })
        require(issues.all { it.path.isNotBlank() && it.type in setOf("TEXT", "IMAGE") && it.rule.isNotBlank() && it.reason.isNotBlank() })
        return this
    }
}
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
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()).apply { setReadTimeout(timeout) }).build()
    override fun moderate(input: JsonNode): ModerationResult = client.post().uri("/api/v1/moderation/story")
        .headers { headers -> CorrelationHeaders.forwardingHeadersFromMdc().forEach { (name, value) -> headers.set(name, value) } }
        .body(input).retrieve().body(ModerationResult::class.java)?.validated() ?: error("Empty moderation response")
}

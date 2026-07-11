package com.knk.manyak.docs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * KNK-525(KNK-524 후속): 에러 응답이 OpenAPI 문서에 `ApiErrorResponse` 바디 스키마로 노출되는지 검증한다.
 *
 * `@ApiResponse`의 content가 `Schema(hidden = true)`이면 springdoc이 content를 통째로 빼서 프론트 codegen이
 * 402 등의 응답 타입(`code` 포함)을 만들지 못한다. 이를 `Schema(implementation = ApiErrorResponse::class)`로
 * 되돌려, 에러 바디가 `application/json` + `$ref: ApiErrorResponse`로 문서화됨을 고정한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiErrorResponseDocsTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    private fun apiDocs(): JsonNode {
        val json = restTestClient.get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult().responseBody ?: error("/v3/api-docs 응답이 비어 있습니다.")
        return ObjectMapper().readTree(json)
    }

    // 에러 응답 content의 단일 media type 엔트리에서 schema $ref를 읽는다.
    // JSON 엔드포인트는 springdoc 기본값 와일드카드 미디어타입, SSE 엔드포인트는 명시한 application/json으로
    // 문서화되므로, 키에 의존하지 않고 유일한 엔트리를 쓴다(핵심은 $ref가 ApiErrorResponse로 잡히는 것).
    private fun errorSchemaRef(root: JsonNode, path: String, method: String, status: String): String {
        val response = root.path("paths").path(path).path(method).path("responses").path(status)
        assertThat(response.isMissingNode)
            .`as`("$method $path 의 $status 응답이 문서에 있어야 한다")
            .isFalse()
        val content = response.path("content")
        assertThat(content.isObject && content.size() == 1)
            .`as`("$method $path $status 응답에 단일 media type content가 있어야 한다: $content")
            .isTrue()
        return content.fields().next().value.path("schema").path("\$ref").asText()
    }

    @Test
    fun `KNK-524 엔드포인트의 402 에러 바디가 ApiErrorResponse로 문서화된다`() {
        val root = apiDocs()

        // 컴포넌트 스키마에 ApiErrorResponse가 존재한다.
        assertThat(root.path("components").path("schemas").has("ApiErrorResponse")).isTrue()

        // SSE(produces=text/event-stream) 엔드포인트도 에러는 application/json 바디로 문서화된다.
        assertThat(errorSchemaRef(root, "/api/v1/chats/{chatId}/turns/stream", "post", "402"))
            .isEqualTo("#/components/schemas/ApiErrorResponse")
        assertThat(errorSchemaRef(root, "/api/v1/chats/{chatId}/turns/regenerate/stream", "post", "402"))
            .isEqualTo("#/components/schemas/ApiErrorResponse")

        // 평범한 JSON 엔드포인트(간편 제작·스토리라인 생성)의 402.
        assertThat(errorSchemaRef(root, "/api/v1/stories/simple", "post", "402"))
            .isEqualTo("#/components/schemas/ApiErrorResponse")
        assertThat(errorSchemaRef(root, "/api/v1/stories/simple/storylines", "post", "402"))
            .isEqualTo("#/components/schemas/ApiErrorResponse")
    }

    @Test
    fun `400·404 에러 바디도 ApiErrorResponse로 문서화된다`() {
        val root = apiDocs()

        assertThat(errorSchemaRef(root, "/api/v1/chats/{chatId}/turns/stream", "post", "400"))
            .isEqualTo("#/components/schemas/ApiErrorResponse")
        assertThat(errorSchemaRef(root, "/api/v1/chats/{chatId}/turns/stream", "post", "404"))
            .isEqualTo("#/components/schemas/ApiErrorResponse")
        assertThat(errorSchemaRef(root, "/api/v1/stories/simple", "post", "404"))
            .isEqualTo("#/components/schemas/ApiErrorResponse")
    }
}

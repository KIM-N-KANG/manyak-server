package com.knk.manyak.global.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * OpenAPI 문서(/v3/api-docs)에 Bearer 인증 스킴과 인증 요구가 반영되는지 검증(KNK-450).
 * - components.securitySchemes.bearerAuth: HTTP bearer(JWT).
 * - 인증 필수 엔드포인트(예: GET /users/me/credits)는 operation에 security(bearerAuth)를 갖는다.
 * - 공개 엔드포인트(예: GET /stories/{storyId})는 security가 없다(전역 requirement를 걸지 않았음).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocIntegrationTests {


    @Autowired private lateinit var restTestClient: RestTestClient

    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper

    @Test
    fun `채팅 설정은 선택 필드지만 null을 허용하지 않고 하나 이상 필요하며 보조 필드는 숨긴다`() {
        val body = restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        val schema = mapper.readTree(body)["components"]["schemas"]["ChangeChatSettingsRequest"]
        val artifact = java.nio.file.Path.of("build", "reports", "chat-settings-schema.json")
        java.nio.file.Files.createDirectories(artifact.parent)
        java.nio.file.Files.writeString(artifact, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema))
        for ((name, type) in listOf("model" to "string", "longResponse" to "boolean")) {
            val property = schema["properties"][name]
            val types = property["type"].let { if (it.isArray) it.toList().map { value -> value.asString() } else listOf(it.asString()) }
            assertThat(types).isEqualTo(listOf(type))
            assertThat(property.path("nullable").asBoolean()).isFalse()
            assertThat(schema.path("required").toList().map { it.asString() }).doesNotContain(name)
        }
        assertThat(schema["properties"].has("hasSettings")).isFalse()
        assertThat(schema.path("minProperties").asInt()).isEqualTo(1)
    }

    @Test
    fun `이어쓰기 lastTurnId는 nullable int64로 문서화하고 입력 여부 플래그는 숨긴다`() {
        restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.components.schemas.ContinueChatRequest.properties.lastTurnId.type").value<List<String>> {
                assertThat(it).contains("integer", "null").doesNotContain("string")
            }
            .jsonPath("$.components.schemas.ContinueChatRequest.properties.lastTurnId.format").isEqualTo("int64")
            .jsonPath("$.components.schemas.ContinueChatRequest.properties.hasLastTurnId").doesNotExist()
    }

    @Test
    fun `OpenAPI 문서에 Bearer 보안 스킴이 등록된다`() {
        restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.components.securitySchemes.bearerAuth.type").isEqualTo("http")
            .jsonPath("$.components.securitySchemes.bearerAuth.scheme").isEqualTo("bearer")
            .jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").isEqualTo("JWT")
    }

    @Test
    fun `TokenResponse 스키마는 런타임 직렬화와 같은 isNewUser 필드명으로 문서화된다`() {
        // springdoc 인트로스펙션(Jackson 2 빈 규약)은 is 접두를 떼고 newUser로 만들지만, 런타임(Jackson 3
        // Kotlin 모듈)은 isNewUser로 직렬화한다. @Schema(name)으로 실제 와이어 필드명을 고정했는지 검증한다.
        restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.components.schemas.TokenResponse.properties.isNewUser").exists()
            .jsonPath("$.components.schemas.TokenResponse.properties.newUser").doesNotExist()
    }

    @Test
    fun `StoryDetailResponse 스키마는 런타임 직렬화와 같은 isOwner 필드명으로 문서화된다`() {
        // isNewUser와 같은 함정(KNK-1018): is 접두가 인트로스펙션에서 벗겨지지 않도록 JsonProperty로 고정했는지 검증한다.
        restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.components.schemas.StoryDetailResponse.properties.isOwner").exists()
            .jsonPath("$.components.schemas.StoryDetailResponse.properties.owner").doesNotExist()
    }

    @Test
    fun `StoryDetailResponse 스키마는 런타임 직렬화와 같은 isLiked 필드명으로 문서화된다`() {
        // isOwner와 같은 함정(KNK-1017): is 접두가 인트로스펙션에서 벗겨지지 않도록 JsonProperty로 고정했는지 검증한다.
        restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.components.schemas.StoryDetailResponse.properties.isLiked").exists()
            .jsonPath("$.components.schemas.StoryDetailResponse.properties.liked").doesNotExist()
    }

    @Test
    fun `isDefault 응답 필드는 런타임 직렬화와 같은 isDefault 필드명으로 문서화된다`() {
        // isNewUser와 같은 함정: springdoc이 is 접두를 벗겨 default로 문서화하면 웹 Orval 생성 타입이 어긋난다.
        restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.components.schemas.ChatModelResponse.properties.isDefault").exists()
            .jsonPath("$.components.schemas.ChatModelResponse.properties.default").doesNotExist()
            .jsonPath("$.components.schemas.AdminPushEffectiveTextResponse.properties.isDefault").exists()
            .jsonPath("$.components.schemas.AdminPushEffectiveTextResponse.properties.default").doesNotExist()
    }

    @Test
    fun `관리자 회원 응답의 isAdmin은 런타임 직렬화와 같은 isAdmin 필드명으로 문서화된다`() {
        val body = restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
        for (schema in listOf("AdminMemberSummary", "AdminMemberListResponse", "AdminMemberDetailResponse")) {
            body.jsonPath("$.components.schemas.$schema.properties.isAdmin").exists()
                .jsonPath("$.components.schemas.$schema.properties.admin").doesNotExist()
        }
    }

    @Test
    fun `간편 제작 요청의 isRegenerated는 스펙 와이어 이름 그대로 문서화된다`() {
        val body = restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
        for (schema in listOf("GenerateSimpleStorylinesRequest", "CreateSimpleStoryRequest")) {
            body.jsonPath("$.components.schemas.$schema.properties.isRegenerated").exists()
                .jsonPath("$.components.schemas.$schema.properties.regenerated").doesNotExist()
        }
    }

    @Test
    fun `스토리 좋아요는 계정 상태 거부(401·403)를 실제 동작대로 문서화한다`() {
        // SuspensionGuard가 정지를 403, 탈퇴를 401로 막는데 문서에는 403이 빠져 있었다(KNK-1017 3차 리뷰).
        restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.paths['/api/v1/stories/{storyId}/like'].post.responses.401").exists()
            .jsonPath("$.paths['/api/v1/stories/{storyId}/like'].post.responses.403").exists()
            .jsonPath("$.paths['/api/v1/stories/{storyId}/like'].delete.responses.401").exists()
            .jsonPath("$.paths['/api/v1/stories/{storyId}/like'].delete.responses.403").exists()
    }

    @Test
    fun `인증 필수 엔드포인트는 bearerAuth security를 갖고 공개 엔드포인트는 갖지 않는다`() {
        restTestClient.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody()
            // 인증 필수: 크레딧 잔액 조회.
            .jsonPath("$.paths['/api/v1/users/me/credits'].get.security[0].bearerAuth").exists()
            // 인증 필수: 스토리 좋아요 등록·취소(KNK-1017 — 게스트 불가).
            .jsonPath("$.paths['/api/v1/stories/{storyId}/like'].post.security[0].bearerAuth").exists()
            .jsonPath("$.paths['/api/v1/stories/{storyId}/like'].delete.security[0].bearerAuth").exists()
            // 공개(permitAll·optional 인증): 스토리 상세 — security를 걸지 않는다.
            .jsonPath("$.paths['/api/v1/stories/{storyId}'].get.security").doesNotExist()
    }
}

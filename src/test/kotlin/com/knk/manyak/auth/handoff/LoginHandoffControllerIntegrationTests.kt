package com.knk.manyak.auth.handoff

import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.global.observability.RequestCorrelationFilter
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

/**
 * 로그인 핸드오프 생성·확인·상태 조회 통합 검증(스펙 §4-3-5 로그인 핸드오프).
 *
 * - 생성은 게스트 호출이라 인증이 없고 `X-Manyak-Device-Id`가 필수다(누락 400).
 * - 코드는 URL이 아니라 `X-Manyak-Handoff-Code` 헤더로만 받는다(요청 URI가 로그·Sentry에 남으므로).
 * - 없는 코드와 만료된 코드는 동일하게 404다(열거 오라클 방지).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginHandoffControllerIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `핸드오프를 생성하면 코드와 분석용 id, 만료 시각을 돌려준다`() {
        val response = createHandoff(storyIds = listOf(UUID.randomUUID().toString()))

        assertThat(response.handoffCode).isNotBlank()
        assertThat(response.handoffId).isNotBlank()
        // 코드와 분석용 id는 별개 값이다(분석 이벤트에는 handoffId만 싣는다).
        assertThat(response.handoffCode).isNotEqualTo(response.handoffId)
        assertThat(response.expiresAt).isNotBlank()
    }

    @Test
    fun `디바이스 헤더 없이 생성하면 400이다`() {
        restTestClient.post().uri(HANDOFFS_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(createBody())
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `확인 호출은 옮길 건수와 복귀 경로를 돌려주고 상태를 LANDED로 바꾼다`() {
        val created = createHandoff(
            storyIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
            chatIds = listOf(UUID.randomUUID().toString()),
        )

        restTestClient.get().uri(HANDOFFS_PATH)
            .header(HEADER_HANDOFF_CODE, created.handoffCode)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.storyCount").isEqualTo(2)
            .jsonPath("$.chatCount").isEqualTo(1)
            .jsonPath("$.callbackPath").isEqualTo(CALLBACK_PATH)

        assertThat(fetchStatus(created.handoffCode).status).isEqualTo("LANDED")
    }

    @Test
    fun `생성 직후 상태는 PENDING이다`() {
        val created = createHandoff()

        val status = fetchStatus(created.handoffCode)

        assertThat(status.status).isEqualTo("PENDING")
        assertThat(status.migratedStoryIds).isEmpty()
        assertThat(status.migratedChatIds).isEmpty()
    }

    @Test
    fun `없는 코드로 확인하면 404다`() {
        restTestClient.get().uri(HANDOFFS_PATH)
            .header(HEADER_HANDOFF_CODE, "존재하지-않는-코드")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `없는 코드로 상태를 조회하면 404다`() {
        restTestClient.get().uri(STATUS_PATH)
            .header(HEADER_HANDOFF_CODE, "존재하지-않는-코드")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `앱 밖으로 나가는 복귀 경로는 400으로 거부한다`() {
        listOf("//evil.com", "https://evil.com", "/\\evil.com", "evil").forEach { path ->
            restTestClient.post().uri(HANDOFFS_PATH)
                .header(RequestCorrelationFilter.HEADER_DEVICE_ID, DEVICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .body(createBody(callbackPath = path))
                .exchange()
                .expectStatus().isBadRequest
        }
    }

    @Test
    fun `알 수 없는 출처 앱은 400으로 거부한다`() {
        restTestClient.post().uri(HANDOFFS_PATH)
            .header(RequestCorrelationFilter.HEADER_DEVICE_ID, DEVICE_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .body(createBody(sourceApp = "facebook"))
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `UUID 형식이 아닌 ID가 섞이면 400이다`() {
        // 미인증 엔드포인트라 임의 길이 문자열을 그대로 보관하면 Redis 메모리 증폭 통로가 된다.
        // 형식을 UUID로 고정해 항목 크기를 묶는다(이관 제출 시점에도 어차피 400이 될 값이다).
        restTestClient.post().uri(HANDOFFS_PATH)
            .header(RequestCorrelationFilter.HEADER_DEVICE_ID, DEVICE_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .body(createBody(storyIds = listOf("not-a-uuid")))
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `ID 배열이 상한을 넘으면 400이다`() {
        val tooMany = (1..101).map { UUID.randomUUID().toString() }

        restTestClient.post().uri(HANDOFFS_PATH)
            .header(RequestCorrelationFilter.HEADER_DEVICE_ID, DEVICE_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .body(createBody(storyIds = tooMany))
            .exchange()
            .expectStatus().isBadRequest
    }

    private fun createBody(
        storyIds: List<String> = emptyList(),
        chatIds: List<String> = emptyList(),
        callbackPath: String = CALLBACK_PATH,
        sourceApp: String = "instagram",
    ): Map<String, Any> = mapOf(
        "storyIds" to storyIds,
        "chatIds" to chatIds,
        "callbackPath" to callbackPath,
        "sourceApp" to sourceApp,
    )

    private fun createHandoff(
        storyIds: List<String> = emptyList(),
        chatIds: List<String> = emptyList(),
        deviceId: String = DEVICE_ID,
    ): CreatedHandoff = restTestClient.post().uri(HANDOFFS_PATH)
        .header(RequestCorrelationFilter.HEADER_DEVICE_ID, deviceId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(createBody(storyIds = storyIds, chatIds = chatIds))
        .exchange()
        .expectStatus().isCreated
        .expectBody(CreatedHandoff::class.java)
        .returnResult()
        .responseBody!!

    private fun fetchStatus(code: String): HandoffStatus = restTestClient.get().uri(STATUS_PATH)
        .header(HEADER_HANDOFF_CODE, code)
        .exchange()
        .expectStatus().isOk
        .expectBody(HandoffStatus::class.java)
        .returnResult()
        .responseBody!!

    data class CreatedHandoff(
        val handoffCode: String = "",
        val handoffId: String = "",
        val expiresAt: String = "",
    )

    data class HandoffStatus(
        val status: String = "",
        val migratedStoryIds: List<String> = emptyList(),
        val migratedChatIds: List<String> = emptyList(),
    )

    private companion object {
        const val HANDOFFS_PATH = "/api/v1/auth/handoffs"
        const val STATUS_PATH = "/api/v1/auth/handoffs/status"
        const val HEADER_HANDOFF_CODE = "X-Manyak-Handoff-Code"
        const val CALLBACK_PATH = "/chats/3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        const val DEVICE_ID = "device-in-app-instagram"
    }
}

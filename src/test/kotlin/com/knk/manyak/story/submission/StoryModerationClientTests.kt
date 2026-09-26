package com.knk.manyak.story.submission

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

class StoryModerationClientTests {
    @Test fun `camelCase 전체 입력과 snake_case 실행 오류를 교환한다`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"decision":"REJECTED","issues":[],"error_code":"IMAGE_READ_FAILED"}"""))
            val client = RestStoryModerationClient(server.url("/").toString(), Duration.ofSeconds(180))
            val result = client.moderate(JsonMapper().readTree("""{"title":"검수","thumbnailUrl":"https://example.test/image"}"""))
            assertEquals("IMAGE_READ_FAILED", result.errorCode)
            val request = server.takeRequest()
            assertEquals("/api/v1/moderation/story", request.path)
            assertTrue(request.body.readUtf8().contains("thumbnailUrl"))
        }
    }
    @Test fun `AI 제한 이하의 클라이언트 타임아웃은 설정 오류`() {
        assertThrows(IllegalArgumentException::class.java) { RestStoryModerationClient("http://localhost", Duration.ofSeconds(150)) }
    }
    @Test fun `모순된 판정 및 알 수 없는 오류 코드는 실행 실패로 분류할 예외`() {
        listOf(ModerationResult("UNKNOWN", emptyList(), null), ModerationResult("APPROVED", emptyList(), "IMAGE_READ_FAILED"),
            ModerationResult("REJECTED", emptyList(), null), ModerationResult("REJECTED", emptyList(), "UNKNOWN"))
            .forEach { assertThrows(IllegalArgumentException::class.java) { it.validated() } }
    }
}

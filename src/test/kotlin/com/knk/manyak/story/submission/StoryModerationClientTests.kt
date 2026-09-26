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
            .forEach { assertThrows(IllegalArgumentException::class.java) { it.validated(JsonMapper().readTree("{}")) } }
    }
    @Test fun `7개 허용 rule과 실제 중첩 텍스트 이미지 경로는 반려로 유지한다`() {
        val input = JsonMapper().readTree("""{"title":"제목","genres":["판타지"],"thumbnailUrl":"url",
            "characters":[{"images":[{"imageName":"이름","imageUrl":"url"}]}],
            "startSettings":[{"endings":[{"requirement":{"achievementCondition":"조건"}}]}]}""")
        val rules = listOf("MINOR_SEXUAL_EXPLOITATION", "EXPLICIT_SEXUAL_CONTENT", "NONCONSENSUAL_SEXUAL_EXPLOITATION",
            "DRUGS", "EXTREME_GORE", "SELF_HARM_PROMOTION", "HATE_VIOLENCE_INCITEMENT")
        val fields = mapOf("title" to "TEXT", "genres[0]" to "TEXT", "thumbnailUrl" to "IMAGE",
            "characters[0].images[0].imageName" to "TEXT", "characters[0].images[0].imageUrl" to "IMAGE",
            "startSettings[0].endings[0].requirement.achievementCondition" to "TEXT")
        rules.forEach { rule -> fields.forEach { (path, type) ->
            val result = ModerationResult("REJECTED", listOf(ModerationIssue(path, type, rule, "사유")), null)
            assertEquals(result, result.validated(input))
        } }
    }

    @Test fun `REST 응답의 알 수 없는 rule과 입력에 없는 경로 및 종류 불일치는 거절한다`() {
        val input = JsonMapper().readTree("""{"title":"제목","characters":[{"images":[{"imageUrl":"url"}]}]}""")
        listOf("title" to "UNKNOWN", "visibility" to "DRUGS", "characters[1].images[0].imageUrl" to "DRUGS",
            "characters" to "DRUGS", "characters[0].images[0].imageUrl" to "DRUGS").forEach { (path, rule) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"decision":"REJECTED","issues":[{"path":"$path","type":"TEXT","rule":"$rule","reason":"사유"}],"error_code":null}"""))
                val client = RestStoryModerationClient(server.url("/").toString(), Duration.ofSeconds(180))
                assertThrows(IllegalArgumentException::class.java) { client.moderate(input) }
            }
        }
    }

}

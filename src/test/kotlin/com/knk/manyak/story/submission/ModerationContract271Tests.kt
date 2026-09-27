package com.knk.manyak.story.submission

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

class ModerationContract271Tests {
    private val mapper = JsonMapper()
    private val input = mapper.readTree("""{"submissionId":"submission-uuid","storyId":"story-uuid","title":"제목",
        "thumbnailUrl":"https://cdn.test/cover","characters":[{"images":[{"imageUrl":"https://cdn.test/face"}]}]}""")
    private val issue = """{"path":"title","type":"TEXT","rule":"DRUGS","reason":"사유","future":"ignored"}"""
    private fun response(body: String): ModerationResult = MockWebServer().use { server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        RestStoryModerationClient(server.url("/").toString(), Duration.ofSeconds(180)).moderate(input)
    }

    @Test fun `새 이미지 오류와 내용 위반을 함께 읽고 알 수 없는 필드는 무시한다`() {
        listOf("IMAGE_DOWNLOAD_FAILED", "IMAGE_INVALID", "IMAGE_UNREADABLE").forEach { code ->
            val result = response("""{"decision":"REJECTED","issues":[$issue],"error_code":"$code",
                "image_errors":[{"path":"thumbnailUrl","error_code":"$code","future":true}],"future":{"value":1}}""")
            assertEquals(code, result.errorCode)
            assertEquals(1, result.issues.size)
            assertEquals(code, mapper.valueToTree<tools.jackson.databind.JsonNode>(result)["image_errors"][0]["error_code"].asText())
        }
        assertEquals("APPROVED", response("""{"decision":"APPROVED","issues":[],"error_code":null,"image_errors":[],"future":1}""").decision)
    }

    @Test fun `이미지 오류 불변식 위반을 거부한다`() {
        val image = """{"path":"thumbnailUrl","error_code":"IMAGE_INVALID"}"""
        val invalid = listOf(
            """{"decision":"APPROVED","issues":[],"error_code":null,"image_errors":[$image]}""",
            """{"decision":"REJECTED","issues":[$issue],"error_code":null,"image_errors":[$image]}""",
            """{"decision":"REJECTED","issues":[],"error_code":"IMAGE_INVALID","image_errors":[]}""",
            """{"decision":"REJECTED","issues":[],"error_code":"MODEL_CALL_FAILED","image_errors":[$image]}""",
            """{"decision":"REJECTED","issues":[$issue],"error_code":"MODEL_CALL_FAILED","image_errors":[]}""",
            """{"decision":"REJECTED","issues":[],"error_code":"IMAGE_DOWNLOAD_FAILED","image_errors":[$image]}""",
            """{"decision":"REJECTED","issues":[],"error_code":"IMAGE_INVALID","image_errors":[$image,$image]}""",
        ) + listOf("title", "submissionId", "storyId", "characters[1].images[0].imageUrl", "characters").map { path ->
            """{"decision":"REJECTED","issues":[],"error_code":"IMAGE_INVALID","image_errors":[{"path":"$path","error_code":"IMAGE_INVALID"}]}"""
        } + listOf("IMAGE_READ_FAILED", "UNKNOWN", "MODEL_CALL_FAILED").map { code ->
            """{"decision":"REJECTED","issues":[],"error_code":"IMAGE_INVALID","image_errors":[{"path":"thumbnailUrl","error_code":"$code"}]}"""
        }
        invalid.forEach { body -> assertThrows(IllegalArgumentException::class.java, { response(body) }, body) }
    }

    @Test fun `관측 식별자는 내용 위반 경로로 허용하지 않는다`() {
        listOf("submissionId", "storyId").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                ModerationResult("REJECTED", listOf(ModerationIssue(path, "TEXT", "DRUGS", "사유")), null).validated(input)
            }
        }
    }

    @Test fun `이미지 오류 최상위 코드는 우선순위를 따른다`() {
        val result = response("""{"decision":"REJECTED","issues":[],"error_code":"IMAGE_INVALID","image_errors":[
            {"path":"thumbnailUrl","error_code":"IMAGE_DOWNLOAD_FAILED"},
            {"path":"characters[0].images[0].imageUrl","error_code":"IMAGE_INVALID"}]}""")
        assertEquals("IMAGE_INVALID", result.errorCode)
    }
}

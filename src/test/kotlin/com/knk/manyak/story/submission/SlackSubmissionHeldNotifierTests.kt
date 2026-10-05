package com.knk.manyak.story.submission

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.TimeUnit

class SlackSubmissionHeldNotifierTests {
    @Test fun `보류 웹훅은 공개 ID 종류 사유 시도 횟수만 담는다`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val id = UUID.randomUUID()
            SlackSubmissionHeldNotifier(server.url("/hook").toString()).onHeld(SubmissionHeld(id, SubmissionKind.CREATE, "MODEL_CALL_FAILED", 3))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            val body = JsonMapper().readTree(request.body.readUtf8())
            assertEquals(setOf("text"), body.properties().map { it.key }.toSet())
            assertEquals("submissionId=$id\nkind=CREATE\nreason=MODEL_CALL_FAILED\nattempts=3", body["text"].asText())
        }
    }
    @Test fun `미설정과 웹훅 실패는 보류 처리를 깨지 않는다`() {
        val event = SubmissionHeld(UUID.randomUUID(), SubmissionKind.UPDATE, "MODERATION_UNAVAILABLE", 3)
        assertDoesNotThrow { SlackSubmissionHeldNotifier("").onHeld(event) }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            assertDoesNotThrow { SlackSubmissionHeldNotifier(server.url("/hook").toString()).onHeld(event) }
        }
    }
}

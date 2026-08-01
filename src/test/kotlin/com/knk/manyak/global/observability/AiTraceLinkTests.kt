package com.knk.manyak.global.observability

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AiTraceLink]가 도메인 연결 식별자를 AI 호출 헤더로 변환하는 규칙(KNK-751)을 고정한다.
 * 핵심 계약은 "값이 있으면 붙이고 없으면 생략"이며(수신 측은 헤더가 없어도 동작해야 한다),
 * 순차 PK가 아닌 public_id(UUID)만 나간다.
 */
class AiTraceLinkTests {

    @Test
    fun `값이 있는 식별자만 헤더로 변환한다`() {
        val creationId = UUID.randomUUID()

        // 스토리라인 id는 Long 그대로다(스펙 §4-4 — 생성 퍼널 임시 리소스라 소유 개념이 없어 Long 노출이 확정돼 있다).
        val headers = AiTraceLink(
            creationId = creationId,
            storylineId = 42L,
            storylineOrder = 2,
        ).toHeaders()

        assertEquals(
            mapOf(
                "X-Manyak-Creation-Id" to creationId.toString(),
                "X-Manyak-Storyline-Id" to "42",
                "X-Manyak-Storyline-Order" to "2",
            ),
            headers,
        )
    }

    @Test
    fun `채팅 여정 식별자를 모두 헤더로 변환한다`() {
        val creationId = UUID.randomUUID()
        val storyId = UUID.randomUUID()
        val chatId = UUID.randomUUID()
        val startSettingId = UUID.randomUUID()

        val headers = AiTraceLink(
            creationId = creationId,
            storyId = storyId,
            chatId = chatId,
            startSettingId = startSettingId,
            turnNumber = 3,
            isRegenerated = true,
        ).toHeaders()

        assertEquals(creationId.toString(), headers["X-Manyak-Creation-Id"])
        assertEquals(storyId.toString(), headers["X-Manyak-Story-Id"])
        assertEquals(chatId.toString(), headers["X-Manyak-Chat-Id"])
        assertEquals(startSettingId.toString(), headers["X-Manyak-Start-Setting-Id"])
        assertEquals("3", headers["X-Manyak-Turn-Number"])
        assertEquals("true", headers["X-Manyak-Is-Regenerated"])
    }

    @Test
    fun `아무 값도 없으면 헤더를 만들지 않는다`() {
        assertTrue(AiTraceLink().toHeaders().isEmpty(), "값이 없으면 헤더를 생략해야 한다")
    }

    @Test
    fun `is_regenerated는 false도 값이므로 헤더로 내보낸다`() {
        // 서버가 채우는 채팅 재생성 플래그는 false가 "재생성 아님"이라는 정보다(누락과 다르다).
        assertEquals("false", AiTraceLink(isRegenerated = false).toHeaders()["X-Manyak-Is-Regenerated"])
    }

    @Test
    fun `부모 생성 식별자는 프론트가 준 값만 싣는다`() {
        val parentCreationId = UUID.randomUUID()

        assertEquals(
            parentCreationId.toString(),
            AiTraceLink(parentCreationId = parentCreationId).toHeaders()["X-Manyak-Parent-Creation-Id"],
        )
        assertTrue("X-Manyak-Parent-Creation-Id" !in AiTraceLink().toHeaders())
    }
}

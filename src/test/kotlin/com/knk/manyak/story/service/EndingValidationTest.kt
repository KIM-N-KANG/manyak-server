package com.knk.manyak.story.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException

/** KNK-523: 시작 설정 내 엔딩 이름 유니크 가드(이름 기반 도달 표시의 모호성 방지). */
class EndingValidationTest {

    @Test
    fun `중복 없는 이름과 빈 목록은 통과한다`() {
        requireDistinctEndingNames(listOf("해피", "노말", "배드"))
        requireDistinctEndingNames(emptyList())
    }

    @Test
    fun `이름이 중복되면 400을 던진다`() {
        val ex = assertThrows<ResponseStatusException> {
            requireDistinctEndingNames(listOf("해피", "해피"))
        }
        assertEquals(400, ex.statusCode.value())
    }
}

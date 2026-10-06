package com.knk.manyak.chat.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatJudgmentNameMappingTests {
    @Test fun `뒤에 있는 원래 번호 이름도 예약한다`() {
        val names = listOf("{username}의 귀환", "민우의 귀환", "민우의 귀환 (2)", "{username}의 귀환 (2)")
        val mapping = ChatJudgmentNameMapping(names, "민우")
        assertEquals(listOf("민우의 귀환", "민우의 귀환 (3)", "민우의 귀환 (2)", "민우의 귀환 (2) (2)"), names.map(mapping::aiName))
        assertEquals("민우의 귀환", mapping.originalName("민우의 귀환 (3)"))
        assertEquals("{username}의 귀환", mapping.originalName("민우의 귀환"))
        assertNull(mapping.originalName(" 민우의 귀환"))
        assertNull(mapping.originalName("알 수 없음"))
    }
}

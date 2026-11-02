package com.knk.manyak.story.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class StoryExploreCursorTests {
    @Test fun `시드와 공개 UUID의 MD5는 PostgreSQL과 H2에서 확인한 값과 같다`() {
        // 실제 PostgreSQL md5와 H2 HASH MD5의 소문자 16진수 결과로 고정한 계약 벡터다.
        assertEquals("4b88170aef75231405ffc6e905f9fa25",
            StoryExploreCursor.hash(42L, UUID.fromString("00000000-0000-0000-0000-000000000001")))
    }
}

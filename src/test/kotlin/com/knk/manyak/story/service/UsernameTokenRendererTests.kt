package com.knk.manyak.story.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class UsernameTokenRendererTests {
    @Test fun `조사와 이름은 한 번만 치환한다`() {
        val suffixes = listOf("이(가)", "은(는)", "을(를)", "과(와)", "아(야)", "(으)로", "으로(로)", "이랑(랑)")
        for ((name, expected) in listOf("민혁" to listOf("이", "은", "을", "과", "아", "으로", "으로", "이랑"), "민우" to listOf("가", "는", "를", "와", "야", "로", "로", "랑"), "Alex" to listOf("가", "는", "를", "와", "야", "로", "로", "랑"))) {
            suffixes.zip(expected).forEach { (suffix, particle) -> assertEquals(name + particle, UsernameTokenRenderer.render("{username}$suffix", name)) }
        }
        assertEquals("하늘로 하늘로", UsernameTokenRenderer.render("{username}(으)로 {username}으로(로)", "하늘"))
        assertEquals("{username}는 {other}", UsernameTokenRenderer.render("{username}은(는) {other}", "{username}"))
        assertEquals("{username}", UsernameTokenRenderer.render("{username}", null))
    }
    @Test fun `토큰과 조사를 절단 경계에서 온전히 제외한다`() {
        for (suffix in listOf("", "이(가)", "으로(로)", "(으)로", "이랑(랑)")) {
            val unit = "{username}$suffix"
            for (limit in 2 until 2 + unit.length) assertEquals("앞 ", UsernameTokenRenderer.truncateRaw("앞 ${unit} 뒤", limit))
            assertEquals("앞 $unit", UsernameTokenRenderer.truncateRaw("앞 ${unit} 뒤", 2 + unit.length))
        }
    }
}

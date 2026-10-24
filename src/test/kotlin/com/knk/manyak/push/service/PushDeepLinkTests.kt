package com.knk.manyak.push.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PushDeepLinkTests {
    @ParameterizedTest
    @ValueSource(strings = ["STORY_COMPLETED", "STORY_MODERATION_COMPLETED", "ATTENDANCE_REMINDER"])
    fun `서비스 종류 코드와 고정 순서로 UTM을 조립한다`(type: String) {
        val code = type.lowercase()
        assertThat(pushDeepLink("https://example.com///", "/studio", type))
            .isEqualTo("https://example.com/studio?utm_source=push&utm_medium=push&utm_campaign=$code&utm_content=$code")
    }

    @ParameterizedTest
    @ValueSource(strings = ["NOTICE", "PROMOTION"])
    fun `관리자 캠페인 값은 공지나 스토리 ID가 아닌 캠페인 공개 ID다`(type: String) {
        val id = "ce240891-8eda-4247-a165-6e10172297bf"
        assertThat(pushDeepLink("https://example.com", "notices/target", type, id))
            .isEqualTo("https://example.com/notices/target?utm_source=push&utm_medium=push&utm_campaign=$id&utm_content=${type.lowercase()}")
    }

    @Test fun `출석 링크의 기존 쿼리를 보존하며 앰퍼샌드로 잇는다`() {
        assertThat(pushDeepLink("https://example.com/", "/my/credits?tab=free", "ATTENDANCE_REMINDER"))
            .isEqualTo("https://example.com/my/credits?tab=free&utm_source=push&utm_medium=push&utm_campaign=attendance_reminder&utm_content=attendance_reminder")
    }

    @Test fun `UTM 값을 인코딩하고 프래그먼트 앞에 쿼리를 둔다`() {
        assertThat(pushDeepLink("https://example.com", "/stories/id?tab=detail#section", "PROMOTION", "campaign A&B+"))
            .isEqualTo("https://example.com/stories/id?tab=detail&utm_source=push&utm_medium=push&utm_campaign=campaign%20A%26B%2B&utm_content=promotion#section")
    }
}

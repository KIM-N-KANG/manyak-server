package com.knk.manyak.story.submission

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class ModerationPushDataTests {
    @ParameterizedTest
    @EnumSource(value = SubmissionStatus::class, names = ["APPROVED", "REJECTED", "FAILED"])
    fun `모든 종료 상태는 스토리 ID와 끝 슬래시에 관계없이 제작 목록으로 이동한다`(status: SubmissionStatus) {
        val submissionId = UUID.randomUUID().toString()
        for (storyId in listOf(null, UUID.randomUUID().toString())) {
            for (base in listOf("https://example.com", "https://example.com/", "https://example.com///")) {
                val data = StoryModerationCompleted(1L, submissionId, storyId, status, 1, "제목").pushData(base)
                assertEquals("https://example.com/studio", data["deepLink"])
                assertEquals("STORY_MODERATION_COMPLETED", data["type"])
                assertEquals(submissionId, data["submissionId"])
                assertEquals(status.name, data["status"])
                assertEquals(storyId, data["storyId"])
                assertEquals(storyId != null, data.containsKey("storyId"))
            }
        }
    }

    @Test
    fun `대기 상태는 검수 완료 푸시로 만들 수 없다`() {
        val event = StoryModerationCompleted(1L, UUID.randomUUID().toString(), null, SubmissionStatus.PENDING, 1, "제목")
        assertThrows(IllegalStateException::class.java) { event.pushData("https://manyak.app") }
    }
}

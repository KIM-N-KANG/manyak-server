package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.story.entity.StoryTakedownReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.*
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class StoryTakedownNotificationTests {
    private val storyId = UUID.randomUUID()
    private fun event(title: String = "달빛 아래의 약속", reason: StoryTakedownReason = StoryTakedownReason.HATE_SPEECH) =
        StoryTakenDown(1, storyId, title, reason, if (reason == StoryTakedownReason.ETC) "실존 인물 비방" else null, Instant.parse("2026-10-27T02:00:00Z"))
    @ParameterizedTest @CsvSource("달빛 아래의 약속,은", "푸른 바다,는", "Love 2,은(는)")
    fun `공개 중단 문구는 한글 받침과 기타 사유를 반영한다`(title: String, particle: String) {
        val e = event(title, StoryTakedownReason.ETC)
        val data = e.pushData("https://manyak.app/")
        assertThat(data["body"]).isEqualTo("「$title」$particle 실존 인물 비방 사유로 공개할 수 없어요. 내용을 수정해 검수를 통과하면 공개할 수 있어요.")
        assertThat(data["title"]).isEqualTo("스토리 공개가 제한됐어요")
        assertThat(data.keys).containsExactlyInAnyOrder("type", "storyId", "title", "body", "deepLink")
        assertThat(data["deepLink"]).isEqualTo("https://manyak.app/studio?utm_source=push&utm_medium=push&utm_campaign=story_taken_down&utm_content=story_taken_down")
        assertThat(e.messageId).isEqualTo("story-taken-down:$storyId:1793066400000")
    }
    @Test fun `치환 뒤 정확히 100 코드포인트인 제목은 그대로 사용한다`() {
        for (name in listOf("가".repeat(20), "😀".repeat(20))) {
            val title = com.knk.manyak.story.service.UsernameTokenRenderer.render("{username}".repeat(5), name)
            assertThat(title.codePointCount(0, title.length)).isEqualTo(100)
            val data = event(title, StoryTakedownReason.ETC).copy(reasonDetail = "사".repeat(100)).pushData("https://manyak.app")
            assertThat(data.getValue("body")).startsWith("「$title」").doesNotContain("…")
            assertThat(data.getValue("body").codePointCount(0, data.getValue("body").length)).isLessThanOrEqualTo(300)
        }
    }
    @Test fun `101 코드포인트 이상은 앞 99 코드포인트와 줄임표로 만들고 서로게이트를 보존한다`() {
        for (title in listOf("가".repeat(101), "가".repeat(300), "가".repeat(98) + "😀" + "나나")) {
            val shortened = title.substring(0, title.offsetByCodePoints(0, 99)) + "…"
            val data = event(title, StoryTakedownReason.ETC).copy(reasonDetail = "사".repeat(100)).pushData("https://manyak.app")
            val body = data.getValue("body")
            assertThat(body).startsWith("「$shortened」은(는) ")
            assertThat(shortened.codePointCount(0, shortened.length)).isEqualTo(100)
            assertThat(body.codePointCount(0, body.length)).isLessThanOrEqualTo(300)
            if (title.contains("😀")) assertThat(body).contains("😀…")
        }
    }
    @Test fun `로컬은 서비스 설정을 확인하고 발송기에서 수신자 식별자를 추가하게 한다`() {
        val users = mock(UserRepository::class.java)
        val sender = mock(FcmPushSender::class.java)
        `when`(users.findById(1L)).thenReturn(Optional.of(User(id = 1, nickname = "작가", servicePushEnabled = true)))
        val e = event()
        StoryTakedownPushListener(users, sender, Executor { it.run() }, "https://manyak.app").takenDown(e)
        verify(sender).sendToUser(1, e.pushData("https://manyak.app"), messageId = e.messageId)
    }
    @Test fun `로컬 서비스 알림 꺼짐은 발송하지 않는다`() {
        val users = mock(UserRepository::class.java)
        val sender = mock(FcmPushSender::class.java)
        `when`(users.findById(1L)).thenReturn(Optional.of(User(id = 1, nickname = "작가", servicePushEnabled = false)))
        val e = event()
        StoryTakedownPushListener(users, sender, Executor { it.run() }, "https://manyak.app").takenDown(e)
        verify(sender).recordDiscarded(1, e.pushData("https://manyak.app"), e.messageId, "SERVICE_PUSH_DISABLED")
        verifyNoMoreInteractions(sender)
    }
    @Test fun `로컬 발송 오류와 포화는 안전한 경고만 남긴다`() {
        val users = mock(UserRepository::class.java)
        val sender = mock(FcmPushSender::class.java)
        val log = org.slf4j.LoggerFactory.getLogger(StoryTakedownPushListener::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        log.addAppender(appender)
        try {
            `when`(users.findById(1L)).thenThrow(IllegalStateException("private exception"))
            StoryTakedownPushListener(users, sender, Executor { it.run() }, "https://manyak.app").takenDown(event())
            StoryTakedownPushListener(users, sender, Executor { throw RejectedExecutionException("private exception") }, "https://manyak.app").takenDown(event())
            assertThat(appender.list.map { it.formattedMessage }).anySatisfy { assertThat(it).contains("story_takedown_push_failed", "IllegalStateException") }
                .anySatisfy { assertThat(it).contains("story_takedown_push_rejected") }
            assertThat(appender.list.joinToString { it.formattedMessage }).doesNotContain("private exception", "달빛", "혐오 표현")
        } finally { log.detachAppender(appender); appender.stop() }
    }
}

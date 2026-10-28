package com.knk.manyak.chat.service

import com.knk.manyak.chat.entity.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChatHistoryContextTests {
    private fun messages() = (1..12).flatMap { turn ->
        listOf(
            StoryMessage(chatId = 1, role = MessageRole.USER, content = "u$turn", messageOrder = turn * 3),
            StoryMessage(chatId = 1, role = MessageRole.SYSTEM, content = "system", messageOrder = turn * 3 + 1),
            StoryMessage(chatId = 1, role = MessageRole.ASSISTANT, content = "a$turn <image>x</image>", messageOrder = turn * 3 + 2),
        )
    }

    @Test
    fun `요약 적용은 실제 AI 응답 순서 뒤 원문만 남긴다`() {
        val context = ChatHistoryContext.of(StoryChat(storyId = 1, summary = "요약", summaryThroughTurn = 10), messages(), true, 13)
        assertThat(context.summary).isEqualTo("요약")
        assertThat(context.history.map { it.content }).containsExactly("u11", "a11 <image>x</image>", "u12", "a12 <image>x</image>")
    }

    @Test
    fun `요약 범위 안 재생성과 선택지는 원문 전체로 구성한다`() {
        val context = ChatHistoryContext.of(StoryChat(storyId = 1, summary = "요약", summaryThroughTurn = 10), messages(), true, 10)
        assertThat(context.summary).isEmpty()
        assertThat(context.history).hasSize(18)
        assertThat(context.history.last().content).startsWith("a9")
    }

    @Test
    fun `요약 범위 밖 재생성과 선택지는 대상 턴을 제외한다`() {
        val chat = StoryChat(storyId = 1, summary = "요약", summaryThroughTurn = 10)
        assertThat(ChatHistoryContext.of(chat, messages(), true, 12).history.map { it.content })
            .containsExactly("u11", "a11 <image>x</image>")
        assertThat(ChatHistoryContext.of(chat, messages(), true, 11).history).isEmpty()
    }

    @Test
    fun `요약 끝 턴이 영이면 기존 summary 값이 있어도 원문으로 요청한다`() {
        val context = ChatHistoryContext.of(StoryChat(storyId = 1, summary = "레거시 값"), messages(), true, 13)
        assertThat(context.summary).isEmpty()
        assertThat(context.history).hasSize(24)
    }

    @Test
    fun `요약을 끄면 저장된 요약 대신 첫 턴부터 보낸다`() {
        val context = ChatHistoryContext.of(StoryChat(storyId = 1, summary = "요약", summaryThroughTurn = 10), messages(), false, 13)
        assertThat(context.summary).isEmpty()
        assertThat(context.history).hasSize(24)
    }
}

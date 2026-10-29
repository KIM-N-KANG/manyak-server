package com.knk.manyak.chat.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

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

    @ParameterizedTest
    @CsvSource("12,13,24", "12,12,22", "16,17,24", "16,16,22", "0,1,24")
    fun `요약 끝 턴이 실제 응답 수보다 크면 요청은 요약 없이 해당 범위 원문을 보낸다`(currentTurn: Int, targetTurn: Int, expectedMessages: Int) {
        val context = ChatHistoryContext.of(StoryChat(storyId = 1, currentTurn = currentTurn, summary = "범위가 잘못된 요약", summaryThroughTurn = 14), messages(), true, targetTurn)
        assertThat(context.summary).isEmpty()
        assertThat(context.history).hasSize(expectedMessages)
        assertThat(context.history.first().content).isEqualTo("u1")
    }

    @Test
    fun `요청 끝 턴이 실제 응답 수를 넘어도 존재하는 원문까지만 보낸다`() {
        val context = ChatHistoryContext.of(StoryChat(storyId = 1, summary = "요약", summaryThroughTurn = 10), messages(), true, 20)
        assertThat(context.summary).isEqualTo("요약")
        assertThat(context.history).hasSize(4)
    }

    @Test
    fun `응답이 없는 채팅의 잘못된 요약 범위도 빈 원문으로 처리한다`() {
        val context = ChatHistoryContext.of(StoryChat(storyId = 1, summary = "잘못된 요약", summaryThroughTurn = 1), emptyList(), true, 3)
        assertThat(context.summary).isEmpty()
        assertThat(context.history).isEmpty()
    }

    @Test
    fun `요약 끝 턴이 실제 응답 수와 같으면 요약을 유지한다`() {
        val context = ChatHistoryContext.of(StoryChat(storyId = 1, summary = "요약", summaryThroughTurn = 12), messages(), true, 13)
        assertThat(context.summary).isEqualTo("요약")
        assertThat(context.history).isEmpty()
    }

    @Test
    fun `요약을 끄면 저장된 요약 대신 첫 턴부터 보낸다`() {
        val context = ChatHistoryContext.of(StoryChat(storyId = 1, summary = "요약", summaryThroughTurn = 10), messages(), false, 13)
        assertThat(context.summary).isEmpty()
        assertThat(context.history).hasSize(24)
    }
}

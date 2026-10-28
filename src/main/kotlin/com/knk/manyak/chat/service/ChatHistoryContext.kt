package com.knk.manyak.chat.service

import com.knk.manyak.chat.client.ChatHistoryMessage
import com.knk.manyak.chat.client.ChatMessageRole
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage

/** 순서 정보가 있는 원문에서 경계를 자른 뒤 와이어 DTO로 변환한다. */
internal data class ChatHistoryContext(val summary: String, val history: List<ChatHistoryMessage>) {
    companion object {
        fun of(chat: StoryChat, messages: List<StoryMessage>, enabled: Boolean, targetTurn: Int): ChatHistoryContext {
            val responseCount = messages.count { it.role == MessageRole.ASSISTANT }
            // 요청 경계는 존재하는 응답까지만 잡는다. 재생성·선택지는 실제 마지막 응답을 제외한다.
            val availableTurns = if (targetTurn <= chat.currentTurn) (responseCount - 1).coerceAtLeast(0) else responseCount
            val historyThrough = if (chat.summaryThroughTurn > responseCount) {
                availableTurns
            } else (targetTurn - 1).coerceIn(0, availableTurns)
            val through = if (enabled && chat.summaryThroughTurn in 1..historyThrough) chat.summaryThroughTurn else 0
            return ChatHistoryContext(
                summary = if (through > 0) chat.summary.orEmpty() else "",
                history = range(messages, through, historyThrough),
            )
        }

        fun range(messages: List<StoryMessage>, afterTurn: Int, throughTurn: Int): List<ChatHistoryMessage> {
            val assistants = messages.filter { it.role == MessageRole.ASSISTANT }
            val lower = if (afterTurn == 0) Int.MIN_VALUE else assistants[afterTurn - 1].messageOrder
            val upper = if (throughTurn == 0) Int.MIN_VALUE else assistants[throughTurn - 1].messageOrder
            return messages.filter { it.messageOrder > lower && it.messageOrder <= upper }.mapNotNull {
                when (it.role) {
                    MessageRole.USER -> ChatHistoryMessage(ChatMessageRole.USER, it.content)
                    MessageRole.ASSISTANT -> ChatHistoryMessage(ChatMessageRole.ASSISTANT, it.content)
                    MessageRole.SYSTEM -> null
                }
            }
        }
    }
}

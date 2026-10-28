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
            val through = if (enabled && chat.summaryThroughTurn < targetTurn) chat.summaryThroughTurn else 0
            return ChatHistoryContext(
                summary = if (through > 0) chat.summary.orEmpty() else "",
                history = range(messages, through, targetTurn - 1),
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

package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.ChatPersonaResponse
import com.knk.manyak.chat.dto.ChatStatus
import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.dto.MessageRole
import com.knk.manyak.chat.entity.*
import com.knk.manyak.chat.repository.*
import com.knk.manyak.auth.security.SuspensionGuard
import com.knk.manyak.story.service.StoryPlayReader
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class ChatForkService(
    private val chats: StoryChatRepository,
    private val messages: StoryMessageRepository,
    private val choices: StoryChoiceRepository,
    private val summaries: StoryChatSummaryRepository,
    private val events: StoryChatMainEventRepository,
    private val stories: StoryPlayReader,
    private val suspension: SuspensionGuard,
) {
    /** 원본의 잠금이나 생성 중 선점 없이 커밋된 한 스냅샷을 복사한다. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun fork(chatId: String, turnId: Long, userId: Long): CreateChatResponse {
        suspension.requireActive(userId)
        val publicId = runCatching { UUID.fromString(chatId) }.getOrElse { throw notFound() }
        val source = chats.findByPublicIdAndDeletedAtIsNull(publicId) ?: throw notFound()
        if (source.userId != userId) throw ResponseStatusException(HttpStatus.FORBIDDEN, "채팅을 포크할 권한이 없습니다.")
        // 입력 수정의 숨김 필터와 같은 읽기 경로를 사용한다. 별도의 포크 전용 메시지 쿼리를 만들지 않는다.
        val visible = messages.findByChatIdOrderByMessageOrderAsc(source.id)
        var pendingUser = false
        var targetTurn = 0
        var target: StoryMessage? = null
        for (message in visible) {
            when (message.role) {
                MessageRole.USER -> pendingUser = true
                MessageRole.ASSISTANT -> {
                    if (pendingUser) {
                        targetTurn += 1
                        if (message.id == turnId) { target = message; break }
                    }
                    pendingUser = false
                }
                MessageRole.SYSTEM -> Unit
            }
        }
        val boundary = target ?: throw notFound()
        val prefix = visible.filter { it.messageOrder <= boundary.messageOrder }
        val hasEnding = prefix.any { it.role == MessageRole.ASSISTANT && (it.reachedEndingId != null || it.reachedEndingNameSnapshot != null) }
        val copy = chats.save(StoryChat(
            userId = userId, storyId = source.storyId, startSettingId = source.startSettingId,
            personaNameSnapshot = source.personaNameSnapshot, personaDescriptionSnapshot = source.personaDescriptionSnapshot,
            creationId = source.creationId, storyPrologueSnapshot = source.storyPrologueSnapshot, title = source.title,
            modelCode = source.modelCode, longResponse = source.longResponse,
            currentTurn = targetTurn, forkedFromChatId = source.id, forkedFromTurn = targetTurn,
            targetMainEventId = source.targetMainEventId, targetProgressTurns = source.targetProgressTurns,
            occurredMainEventNamesSnapshot = source.occurredMainEventNamesSnapshot?.toList(),
            status = if (hasEnding) ChatStatus.ENDED else ChatStatus.ACTIVE,
            reachedEndingId = source.reachedEndingId.takeIf { hasEnding },
            reachedEndingNameSnapshot = source.reachedEndingNameSnapshot.takeIf { hasEnding },
        ))
        val copiedIds = prefix.associate { original ->
            val message = messages.save(StoryMessage(chatId = copy.id, role = original.role,
                content = original.content, messageOrder = original.messageOrder,
                reachedEndingId = original.reachedEndingId, reachedEndingNameSnapshot = original.reachedEndingNameSnapshot,
                modelCode = original.modelCode, createdAt = original.createdAt))
            original.id to message.id
        }
        val assistantIds = prefix.filter { it.role == MessageRole.ASSISTANT }.map { it.id }
        if (assistantIds.isNotEmpty()) choices.saveAll(choices.findByMessageIdInOrderByChoiceOrderAsc(assistantIds).map {
            StoryChoice(chatId = copy.id, messageId = copiedIds.getValue(it.messageId), choiceText = it.choiceText,
                choiceOrder = it.choiceOrder, isSelected = it.isSelected, selectedAt = it.selectedAt,
                isEdited = it.isEdited, createdAt = it.createdAt)
        })
        summaries.saveAll(summaries.findByChatIdOrderByThroughTurnAsc(source.id)
            .filter { it.invalidatedAt == null && it.throughTurn <= targetTurn }
            .map { StoryChatSummary(chatId = copy.id, throughTurn = it.throughTurn, summary = it.summary, createdAt = it.createdAt) })
        events.saveAll(events.findByChatId(source.id).map { StoryChatMainEvent(chatId = copy.id, mainEventId = it.mainEventId, createdAt = it.createdAt) })
        val storyPublicId = stories.findPublicId(source.storyId) ?: throw notFound()
        return CreateChatResponse(id = copy.publicId.toString(), storyId = storyPublicId.toString(),
            persona = copy.personaNameSnapshot?.let(::ChatPersonaResponse), prologue = copy.storyPrologueSnapshot.orEmpty(),
            suggestedInputs = emptyList(), model = copy.modelCode, longResponse = copy.longResponse, createdAt = copy.createdAt)
    }

    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "채팅 또는 턴을 찾을 수 없습니다.")
}

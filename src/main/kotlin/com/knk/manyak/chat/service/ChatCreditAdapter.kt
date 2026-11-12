package com.knk.manyak.chat.service

import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryMessageVersionRepository
import com.knk.manyak.credit.port.ChatStoryLookup
import com.knk.manyak.credit.port.ChatTurnChargeLinks
import org.springframework.stereotype.Component

/** 크레딧 포트의 chat 쪽 구현(KNK-2066). `@Transactional`을 달지 않아 호출자(대사, 이용내역)의 트랜잭션에 참여한다. */
@Component
class ChatCreditAdapter(
    private val messages: StoryMessageRepository,
    private val versions: StoryMessageVersionRepository,
    private val chats: StoryChatRepository,
) : ChatTurnChargeLinks, ChatStoryLookup {

    override fun isLinked(chargeId: Long): Boolean =
        messages.existsByCreditTransactionId(chargeId) || versions.existsByCreditTransactionId(chargeId)

    override fun storyIdsByChatId(chatIds: Collection<Long>): Map<Long, Long> =
        chats.findAllById(chatIds).associate { it.id to it.storyId }
}

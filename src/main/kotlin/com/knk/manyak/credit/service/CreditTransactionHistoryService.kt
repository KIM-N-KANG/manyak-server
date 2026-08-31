package com.knk.manyak.credit.service

import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.dto.CreditTransactionPageResponse
import com.knk.manyak.credit.dto.CreditTransactionResponse
import com.knk.manyak.credit.dto.CreditTransactionType
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.entity.CreditTransaction
import com.knk.manyak.credit.repository.CreditLotRepository
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryRepository
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 크레딧 이용내역 조회(KNK-1044). 원장([CreditTransaction])을 최신순 커서 페이지로 읽어 화면용 DTO로 조립한다.
 *
 * 원장에는 화면이 필요한 두 가지가 없어 조립 단계에서 채운다.
 * - **제목**: 소모 행은 스토리 제목이 정보의 전부다. `ref_type`/`ref_id`로 역참조하되, 페이지 항목을 한 번 훑어
 *   id 집합을 모은 뒤 배치 조회한다(N+1 금지).
 * - **만료일**: 소멸 행의 `created_at`은 실제 만료일이 아니다. 만료 회수가 배치가 아니라 지연 정리라
 *   ([CreditWalletService]가 다음 지갑 락에서 정리) 며칠 늦게 기록된다. 로트의 `expires_at`을 함께 내려준다.
 */
@Service
class CreditTransactionHistoryService(
    private val transactionRepository: CreditTransactionRepository,
    private val lotRepository: CreditLotRepository,
    private val storyRepository: StoryRepository,
    private val storyChatRepository: StoryChatRepository,
    private val sessionRepository: StoryCreationSessionRepository,
    private val cursorCodec: CreditCursorCodec,
) {

    @Transactional(readOnly = true)
    fun history(userId: Long, type: String, limit: Int, cursor: String?): CreditTransactionPageResponse {
        val reasons = filterOf(type)?.reasons ?: CreditTransactionType.historyReasons
        val pageSize = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        // 한 건 더 읽어 다음 페이지 유무를 판정한다. "결과가 limit을 채웠으면 커서를 준다"로 하면 총 건수가
        // limit의 배수일 때 클라이언트가 빈 페이지를 한 번 더 요청하게 된다.
        val pageable = PageRequest.ofSize(pageSize + 1)
        val decoded = cursor?.let(::decodeCursor)
        val fetched = if (decoded == null) {
            transactionRepository.findHistoryFirstPage(userId, reasons, pageable)
        } else {
            transactionRepository.findHistoryAfterCursor(userId, reasons, decoded.createdAt, decoded.id, pageable)
        }
        val hasMore = fetched.size > pageSize
        val rows = if (hasMore) fetched.subList(0, pageSize) else fetched
        val titles = resolveTitles(rows)
        val expiries = resolveExpiries(rows)
        return CreditTransactionPageResponse(
            items = rows.map { row ->
                CreditTransactionResponse(
                    type = typeOf(row.reason),
                    reason = row.reason,
                    amount = row.amount,
                    title = titles[row.id],
                    expiresAt = expiries[row.id],
                    createdAt = row.createdAt,
                )
            },
            // 초과분이 있었다면 다음 페이지가 있다. 이 페이지의 마지막 행이 곧 다음 페이지의 시작 경계다.
            nextCursor = if (hasMore) cursorCodec.encode(CreditCursor(rows.last().createdAt, rows.last().id)) else null,
        )
    }

    /** 조회는 노출 대상 사유로만 좁혀 오므로 분류가 없는 사유(PURCHASE)는 여기까지 오지 않는다. */
    private fun typeOf(reason: CreditReason): CreditTransactionType =
        CreditTransactionType.of(reason) ?: error("이용내역에서 제외된 사유가 조회됐습니다: $reason")

    /** 필터 칩 값 → 분류. `ALL`은 필터 없음(null)이고, 그 외 미지원 값은 400이다. */
    private fun filterOf(type: String): CreditTransactionType? {
        if (type.equals(ALL, ignoreCase = true)) return null
        return CreditTransactionType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 type입니다: $type")
    }

    /**
     * 행 id → 스토리 제목. `ref_type`으로 두 갈래를 나눠 각각 한 번씩만 조회한다.
     *
     * 함정: `ref_type`이 `"STORY"`인 행의 `ref_id`는 스토리 PK가 아니라 **제작 세션 PK**다
     * ([com.knk.manyak.story.service.SimpleStoryCreationService]가 세션 id로 차감한다). 세션을 한 단계 거치지
     * 않고 `stories.id`로 곧장 조인하면 엉뚱한 스토리 제목이 붙는다. 실패한 세션은 `story_id`가 NULL이라 제목도 없다.
     */
    private fun resolveTitles(rows: List<CreditTransaction>): Map<Long, String?> {
        val chatIds = rows.mapNotNullTo(mutableSetOf()) { if (it.refType == REF_CHAT) it.refId else null }
        val sessionIds = rows.mapNotNullTo(mutableSetOf()) { if (it.refType == REF_STORY) it.refId else null }
        if (chatIds.isEmpty() && sessionIds.isEmpty()) return emptyMap()

        val storyIdByChat = storyChatRepository.findAllById(chatIds).associate { it.id to it.storyId }
        val storyIdBySession = sessionRepository.findAllById(sessionIds).associate { it.id to it.storyId }
        val storyIds = (storyIdByChat.values + storyIdBySession.values.filterNotNull()).toSet()
        // 삭제된 스토리는 제목을 내리지 않는다 — 클라이언트가 "삭제된 스토리" 폴백 문구를 쓴다.
        val titleByStory = storyRepository.findAllById(storyIds)
            .filter { it.deletedAt == null }
            .associate { it.id to it.title }

        return rows.mapNotNull { row ->
            val storyId = when (row.refType) {
                REF_CHAT -> storyIdByChat[row.refId]
                REF_STORY -> storyIdBySession[row.refId]
                else -> null
            } ?: return@mapNotNull null
            titleByStory[storyId]?.let { row.id to it }
        }.toMap()
    }

    /** 행 id → 만료일. 획득은 그 적립이 만든 로트(transaction_id 1:1), 소멸은 회수 대상 로트(ref_id)에서 읽는다. */
    private fun resolveExpiries(rows: List<CreditTransaction>): Map<Long, Instant?> {
        val earnRows = rows.filterTo(mutableListOf()) { typeOf(it.reason) == CreditTransactionType.EARN }
        val expiredLotIds = rows.mapNotNullTo(mutableSetOf()) { if (it.refType == REF_CREDIT_LOT) it.refId else null }
        if (earnRows.isEmpty() && expiredLotIds.isEmpty()) return emptyMap()

        val expiryByTransaction = if (earnRows.isEmpty()) {
            emptyMap()
        } else {
            lotRepository.findByTransactionIdIn(earnRows.map { it.id })
                .mapNotNull { lot -> lot.transactionId?.let { it to lot.expiresAt } }
                .toMap()
        }
        val expiryByLot = lotRepository.findAllById(expiredLotIds).associate { it.id to it.expiresAt }

        return rows.mapNotNull { row ->
            val expiresAt = when {
                row.refType == REF_CREDIT_LOT -> expiryByLot[row.refId]
                typeOf(row.reason) == CreditTransactionType.EARN -> expiryByTransaction[row.id]
                else -> null
            } ?: return@mapNotNull null
            row.id to expiresAt
        }.toMap()
    }

    /**
     * 커서 문자열 → 원장 위치. 봉인 해제 실패(형식 오류·변조·다른 키)는 모두 400이다.
     *
     * 커서에는 정렬 타이브레이커인 순차 PK가 들어가므로 인코딩이 아니라 암호화한다([CreditCursorCodec]).
     * 시각은 초와 나노초로 쪼개 정밀도를 보존한다 — 밀리초로 절삭하면 커서 `C`가 실제 시각보다 앞서게 되어
     * `C ≤ created_at < 실제값` 구간의 행이 `< C`에도 `= C`에도 걸리지 않고 다음 페이지에서 누락된다
     * (`created_at`은 마이크로초 정밀도다).
     */
    private fun decodeCursor(cursor: String): CreditCursor = runCatching { cursorCodec.decode(cursor) }
        .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "커서가 올바르지 않습니다.") }

    private companion object {
        const val ALL = "ALL"
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 100

        // 소비자(ChatService·SimpleStoryCreationService·CreditWalletService)가 차감·만료 시 박는 ref_type과 같아야 한다.
        const val REF_CHAT = "CHAT"
        const val REF_STORY = "STORY"
        const val REF_CREDIT_LOT = "CREDIT_LOT"
    }
}

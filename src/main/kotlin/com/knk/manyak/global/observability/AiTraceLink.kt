package com.knk.manyak.global.observability

import java.util.UUID

/**
 * AI 호출을 Langfuse trace 여정(스토리라인 → 컴파일 → 채팅 → 완성)으로 묶기 위한 도메인 연결 식별자(KNK-751).
 *
 * [CorrelationHeaders]가 MDC(요청 스코프)에서 뽑는 request/session/device 식별자와 달리, 여기 담기는 값은
 * 도메인 값이라 MDC에 없다. 그래서 호출부가 인자로 넘기고, AI 클라이언트가 두 묶음을 합쳐 함께 forward한다.
 *
 * 규칙:
 * - 값이 없으면 헤더를 **생략**한다(기존 "오면 붙이고 없으면 생략"과 동일). 수신 측은 헤더가 없어도 동작해야 한다.
 * - 소유 개념이 있는 리소스(스토리·채팅·시작 설정)는 public_id(UUID)만 내보낸다(레포 IDOR 규칙).
 *   반면 [storylineId]는 Long이다 — 스토리라인은 생성 퍼널의 임시 리소스라 소유 개념이 없어 스펙 §4-4가 Long 노출을
 *   이미 확정했고, 같은 값이 compile 요청 본문에도 실려 있어 헤더에 넣는 것이 새 노출이 아니다.
 * - [turnNumber]는 **예측치**(이어쓰기는 current_turn + 1)이지 권위값이 아니다. 최종 대조는 `ai_call_logs.turn_number`가 한다.
 * - [creationId]는 간편 제작 스토리라인 단계의 `story_creation_requests.request_id`다. AI 호출 전에 커밋되고
 *   실패해도 남으며 재시도에도 같은 값이라, 여정 전체를 묶는 키로 쓴다(일반 제작 스토리는 값이 없어 생략된다).
 */
data class AiTraceLink(
    val creationId: UUID? = null,
    val parentCreationId: UUID? = null,
    val storylineId: Long? = null,
    val storylineOrder: Short? = null,
    val storyId: UUID? = null,
    val chatId: UUID? = null,
    val startSettingId: UUID? = null,
    val turnNumber: Int? = null,
    val isRegenerated: Boolean? = null,
) {
    /** 값이 있는 식별자만 outbound 헤더 맵으로 변환한다. */
    fun toHeaders(): Map<String, String> = buildMap {
        putIfPresent(HEADER_CREATION_ID, creationId)
        putIfPresent(HEADER_PARENT_CREATION_ID, parentCreationId)
        putIfPresent(HEADER_STORYLINE_ID, storylineId)
        putIfPresent(HEADER_STORYLINE_ORDER, storylineOrder)
        putIfPresent(HEADER_STORY_ID, storyId)
        putIfPresent(HEADER_CHAT_ID, chatId)
        putIfPresent(HEADER_START_SETTING_ID, startSettingId)
        putIfPresent(HEADER_TURN_NUMBER, turnNumber)
        putIfPresent(HEADER_IS_REGENERATED, isRegenerated)
    }

    private fun MutableMap<String, String>.putIfPresent(name: String, value: Any?) {
        value?.let { put(name, it.toString()) }
    }

    companion object {
        const val HEADER_CREATION_ID = "X-Manyak-Creation-Id"
        const val HEADER_PARENT_CREATION_ID = "X-Manyak-Parent-Creation-Id"
        const val HEADER_STORYLINE_ID = "X-Manyak-Storyline-Id"
        const val HEADER_STORYLINE_ORDER = "X-Manyak-Storyline-Order"
        const val HEADER_STORY_ID = "X-Manyak-Story-Id"
        const val HEADER_CHAT_ID = "X-Manyak-Chat-Id"
        const val HEADER_START_SETTING_ID = "X-Manyak-Start-Setting-Id"
        const val HEADER_TURN_NUMBER = "X-Manyak-Turn-Number"
        const val HEADER_IS_REGENERATED = "X-Manyak-Is-Regenerated"
    }
}

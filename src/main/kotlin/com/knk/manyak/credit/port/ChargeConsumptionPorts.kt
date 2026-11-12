package com.knk.manyak.credit.port

/**
 * 선차감 대사가 "이 차감이 소비됐는가"를 묻는 곳을 크레딧 쪽에서 정의한다(KNK-2066). 소비의 근거(저장된 턴, 제작 진행)는
 * chat과 story가 소유하므로 그 모듈이 구현하고, credit은 chat과 story의 타입을 직접 참조하지 않는다.
 *
 * 구현은 트랜잭션과 잠금을 새로 정하지 않는다. 대사 쪽 트랜잭션(차감 행 잠금을 쥔 행 단위 대사 등)에 그대로 참여한다.
 */
interface ChatTurnChargeLinks {

    /** 참조 종류 `CHAT_TURN` 차감 [chargeId]가 메시지나 메시지 버전 이력에 연결돼 있으면 true. 메시지를 먼저 확인한다. */
    fun isLinked(chargeId: Long): Boolean
}

interface StoryCreationProgress {

    /** 간편 제작 진행 [sessionId]가 스토리를 만들었으면 true, 아니면 false, 진행이 없으면 null. */
    fun isStoryCreated(sessionId: Long): Boolean?
}

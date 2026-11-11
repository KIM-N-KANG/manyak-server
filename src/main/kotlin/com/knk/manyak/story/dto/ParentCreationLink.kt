package com.knk.manyak.story.dto

import java.util.UUID

/**
 * 프론트가 보낸 부모 creation_id와 그 검증 결과(KNK-755).
 *
 * 검증 통과 여부와 무관하게 [attemptedParentCreationId]를 보존해, "애초에 부모가 없는 최초 생성"과
 * "재생성인데 연결에 실패한 행"이 DB에서 구분되게 한다. 헤더·정본 컬럼에 쓰는 값은 [validatedParentRequestId] 하나뿐이라,
 * 미검증 값이 체인으로 새 나가는 경로가 생기지 않는다.
 */
data class ParentCreationLink(
    val attemptedParentCreationId: UUID,
    val error: ParentLinkError?,
) {
    /** 검증을 통과했을 때만 부모 값. 실패면 null(정본 컬럼도 헤더도 비운다). */
    val validatedParentRequestId: UUID?
        get() = attemptedParentCreationId.takeIf { error == null }
}

package com.knk.manyak.story.dto

/** 재생성 체인 부모 링크 검증 실패 사유(KNK-755). 400으로 거부하는 대신 이 사유를 행에 남긴다. */
enum class ParentLinkError {
    /** 그 request_id를 가진 행이 없다. */
    NOT_FOUND,

    /** 부모와 현재 요청의 소유가 이어지지 않는다(다른 회원·다른 기기). */
    OWNER_MISMATCH,

    /** 자기 자신을 부모로 지정했다. */
    SELF_REFERENCE,
}

package com.knk.manyak.story.entity

/** 스토리 등록 상태. 일반 모드 초안은 DRAFT로 시작해 발행 시 PUBLISHED가 된다(KNK-401). */
enum class StoryStatus {
    DRAFT,
    PUBLISHED,
}

/** 스토리 공개 범위. 공개 조회는 PUBLISHED이면서 PUBLIC인 스토리만 노출한다. */
enum class StoryVisibility {
    PUBLIC,
    PRIVATE,
}

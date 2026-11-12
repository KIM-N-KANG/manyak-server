package com.knk.manyak.story.dto

/** 생성 요청 진행 상태. 요청 수신 시 PENDING으로 기록하고, 성공 COMPLETED·실패 FAILED로 갱신한다. */
enum class StoryCreationRequestStatus {
    PENDING,
    COMPLETED,
    FAILED,
}

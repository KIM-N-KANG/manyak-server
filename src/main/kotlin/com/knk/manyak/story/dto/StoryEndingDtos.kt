package com.knk.manyak.story.dto

import com.knk.manyak.story.entity.StoryEnding

/** 엔딩 개수 상한(시작 설정당 최대 10, 스펙 §4-3-8·§4-3-10). */
const val MAX_ENDINGS = 10

/** 활성 엔딩 엔티티를 응답(이름 기반 2파라미터)으로 변환한다. 레거시(enabled=false) 행은 조회에서 제외된다. */
fun StoryEnding.toEndingResponse(): StoryEndingResponse =
    StoryEndingResponse(
        name = name,
        requirement = StoryEndingRequirementResponse(
            minTurns = minTurns,
            achievementCondition = achievementCondition,
        ),
        epilogue = epilogue,
    )

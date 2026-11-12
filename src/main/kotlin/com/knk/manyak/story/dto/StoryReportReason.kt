package com.knk.manyak.story.dto

/** 신고 사유(스펙 §4-3-1 스토리 신고, KNK-1020). 세분화 수요가 생기면 값만 늘린다. */
enum class StoryReportReason {
    SPAM,
    INAPPROPRIATE,
    ETC,
}

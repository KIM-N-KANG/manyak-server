package com.knk.manyak.story.report

import com.knk.manyak.story.entity.StoryReportReason
import java.time.Instant

/** 신고 접수 알림용 이벤트(KNK-1020). 알림에 필요한 표시 정보만 담는다(엔티티 비전달). */
data class StoryReportedEvent(
    val reportId: Long,
    val storyPublicId: String,
    val storyTitle: String,
    val reason: StoryReportReason,
    val detail: String?,
    val createdAt: Instant,
)

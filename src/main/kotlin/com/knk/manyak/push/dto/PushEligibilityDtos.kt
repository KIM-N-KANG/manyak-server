package com.knk.manyak.push.dto

import com.knk.manyak.notice.entity.NoticeTag
import com.knk.manyak.push.entity.PushPlatform

enum class PushKind {
    SERVICE,
    MARKETING,
}

/** 공지 태그가 실리는 푸시 종류. 공지는 푸시를 모르고, 푸시가 공지 태그를 해석한다. */
val NoticeTag.pushKind: PushKind
    get() = when (this) {
        NoticeTag.EVENT, NoticeTag.PROMOTION -> PushKind.MARKETING
        else -> PushKind.SERVICE
    }

data class PushEligibilityResponse(
    val allowed: Boolean,
    val reason: String,
    val tokens: List<PushEligibilityToken> = emptyList(),
)

data class PushEligibilityToken(
    val token: String,
    val platform: PushPlatform,
)

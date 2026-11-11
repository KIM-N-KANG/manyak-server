package com.knk.manyak.push.dto

/** 캠페인 한 건의 생애(KNK-1117). 상태 전이는 `SCHEDULED → SENDING → SENT|FAILED`이고 `CANCELED`는 집기 전에만 들어간다. */
enum class PushCampaignStatus {
    SCHEDULED,
    SENDING,
    SENT,
    CANCELED,
    FAILED,
}

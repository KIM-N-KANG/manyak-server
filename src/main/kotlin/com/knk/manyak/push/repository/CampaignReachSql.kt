package com.knk.manyak.push.repository

/** 도달 요약과 도달 시각 조회가 같은 마지막 회차의 회원 집합을 사용한다. */
internal fun campaignMemberDeliveryCondition(result: String = "r", campaign: String = "c") =
    "$result.campaign_id=$campaign.id AND $campaign.started_at IS NOT NULL AND $result.completed_at>=$campaign.started_at " +
        "AND $result.delivered_devices>0 AND $result.user_id IS NOT NULL"

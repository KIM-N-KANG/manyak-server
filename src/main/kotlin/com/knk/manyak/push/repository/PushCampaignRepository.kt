package com.knk.manyak.push.repository

import com.knk.manyak.push.entity.PushCampaign
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface PushCampaignRepository : JpaRepository<PushCampaign, Long> {

    /** 발송 시각이 지난 예약을 예약 순으로 돌려준다. 한 회차에 여럿이 도래하면 차례로 보낸다. */
    @Query(
        """
        SELECT c FROM PushCampaign c
        WHERE c.status = com.knk.manyak.push.entity.PushCampaignStatus.SCHEDULED
          AND c.scheduledAt <= :now
        ORDER BY c.scheduledAt ASC
        """,
    )
    fun findDueScheduled(@Param("now") now: Instant): List<PushCampaign>

    /**
     * 캠페인을 선점한다. 갱신 1건이면 내 것이고, 0이면 다른 인스턴스가 이미 집었거나 운영자가 취소한 것이다.
     *
     * 배포 교체로 태스크가 둘일 때 같은 캠페인이 두 번 나가는 것을 이 조건부 UPDATE 하나가 막는다 —
     * 출석 리마인드의 Redis `SET NX`가 필요 없는 이유다(선점 대상이 이미 DB 행이라 DB가 직렬화한다).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE PushCampaign c
        SET c.status = com.knk.manyak.push.entity.PushCampaignStatus.SENDING, c.startedAt = :startedAt
        WHERE c.id = :id AND c.status = com.knk.manyak.push.entity.PushCampaignStatus.SCHEDULED
        """,
    )
    fun claim(@Param("id") id: Long, @Param("startedAt") startedAt: Instant): Int
}

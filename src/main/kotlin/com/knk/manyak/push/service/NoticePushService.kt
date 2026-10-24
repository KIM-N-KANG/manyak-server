package com.knk.manyak.push.service

import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.entity.PushCampaign
import com.knk.manyak.push.repository.PushCampaignRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 공지 게시의 트랜잭션에 캠페인만 추가한다. 외부 발송과 캠페인 별도 변경 감사는 실행하지 않는다. */
@Service
class NoticePushService(private val campaigns: PushCampaignRepository) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun request(noticeId: Long, title: String, body: String, kind: PushKind, tag: com.knk.manyak.notice.entity.NoticeTag, at: Instant): PushCampaign =
        campaigns.save(PushCampaign(title = title, body = body, kind = kind, noticeId = noticeId, noticeTag = tag, scheduledAt = at, createdAt = at))
}

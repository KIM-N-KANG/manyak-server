package com.knk.manyak.push.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/**
 * 푸시 알림 문구의 런타임 오버라이드(KNK-1116, V74). 행이 없으면 yml 기본 문구를 쓴다.
 *
 * `credit_policies`(KNK-1056)와 같은 결이지만 PK가 키가 아니다 — 같은 키의 기간별 행을 미리 넣어 두고
 * `effective_from`으로 교체 시점을 예약할 수 있어야 하기 때문이다.
 */
@Entity
@Table(
    name = "push_message_templates",
    indexes = [Index(name = "idx_push_message_templates_key", columnList = "template_key")],
)
class PushMessageTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // com.knk.manyak.push.service.PushTemplateKey의 storageKey와 짝이다. 모르는 키는 조회에서 무시된다.
    @Column(name = "template_key", nullable = false, length = 64)
    val templateKey: String,

    @Column(nullable = false, length = 100)
    val title: String,

    @Column(nullable = false, length = 300)
    val body: String,

    @Column(name = "effective_from", nullable = false)
    val effectiveFrom: Instant = Instant.now(),

    /** NULL이면 상시 적용. 값이 있으면 그 시각부터 yml 기본 문구로 되돌아간다(이벤트 종료 장치). */
    @Column(name = "effective_until")
    val effectiveUntil: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)

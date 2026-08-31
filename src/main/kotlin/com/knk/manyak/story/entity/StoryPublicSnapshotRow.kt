package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * 스토리의 마지막 공개 버전 스냅샷을 담는 행(KNK-1065). 내용은 [StoryPublicSnapshot]이다.
 *
 * **`stories`의 컬럼이 아니라 별도 테이블인 이유**(PR #224 Codex P2): JPA basic 매핑은 기본이 eager라
 * `stories` 행을 뜰 때마다 이 JSON 전체를 읽고 역직렬화한다. 스토리 목록·`/stories/batch`(최대 100건)·
 * 서재·오리지널 목록이 전부 스토리 엔티티를 긁으면서 이 값은 쓰지 않고, 설정 본문에 입력 상한이 없어
 * 한 건이 수십 KB까지 커질 수 있다. `@Basic(fetch = LAZY)`는 Hibernate 바이트코드 인핸스먼트가 켜져 있어야
 * 실제로 지연되는데 이 레포는 켜져 있지 않아(빌드에 `org.hibernate.orm` 플러그인 없음) 조용히 무시된다.
 * 테이블을 나누면 스냅샷이 필요한 읽기 경로만 따로 조회한다.
 *
 * 행이 없으면 "마지막 공개 시점을 모른다"는 뜻이다 — 한 번도 공개된 적 없거나, V68 백필 시점에 이미
 * 비공개·초안·삭제였던 스토리다.
 */
@Entity
@Table(name = "story_public_snapshots")
class StoryPublicSnapshotRow(
    // 스토리당 하나뿐이라 story_id가 곧 PK다(대리키를 두지 않는다). FK는 ON DELETE CASCADE.
    @Id
    @Column(name = "story_id", nullable = false, updatable = false)
    val storyId: Long = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false)
    var snapshot: StoryPublicSnapshot = StoryPublicSnapshot(),

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}

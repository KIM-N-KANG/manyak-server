package com.knk.manyak.image.entity

import com.knk.manyak.story.entity.StoryCreationTag
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import java.time.Instant

/**
 * 팀 제작 이미지 자산의 종류. `prefix`는 S3 객체 키와 서빙 URL의 경로 조각이다(스펙 §4-3-9).
 */
enum class ImagePresetType(val prefix: String) {
    THUMBNAIL("thumbnails"),
    BACKGROUND("backgrounds"),
    CHARACTER("characters"),
}

/**
 * 팀 제작 이미지 카탈로그. 런타임 매칭의 정본이며, 원본 파일명은 시드 매니페스트의 입력일 뿐이다(스펙 §4-3-9).
 *
 * `imageKey`는 불변이고 이미지 교체는 새 키 발급이다 — 저장된 지난 턴이 언제 봐도 같아야 하기 때문이다.
 * 서빙 URL은 저장하지 않고 [com.knk.manyak.image.service.ImageUrlResolver]가 조합한다.
 *
 * 카탈로그 행은 삭제하지 않는다. 운영 제외는 [deactivatedAt] 기록으로만 하며, 불리언이 아니라 시각인 이유는
 * 지난 턴 `images[]` 재구성이 "그 턴의 확정 시각 시점에 활성이었나"를 판정해야 하기 때문이다(KNK-544).
 *
 * 의미 태그 3축의 뜻은 타입마다 다르다: [mood]는 분위기(THUMBNAIL·BACKGROUND) 또는 성격(CHARACTER),
 * [subject]는 장소(THUMBNAIL·BACKGROUND) 또는 성별(CHARACTER), [prop]은 공통으로 소품이다.
 */
@Entity
@Table(name = "image_presets")
class ImagePreset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "image_key", nullable = false, unique = true, updatable = false, length = 64)
    val imageKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: ImagePresetType,

    @Column(length = 50)
    val mood: String? = null,

    @Column(length = 50)
    val subject: String? = null,

    @Column(length = 50)
    val prop: String? = null,

    // 장르는 복수(썸네일 최대 3개)이며 태그 마스터를 참조한다 — 값이 GENRE 마스터 태그명과 정확히 일치해야
    // 스토리의 장르 문자열과 동등 비교로 매칭된다(스펙 §4-3-9 자동 연결).
    @ManyToMany
    @JoinTable(
        name = "image_preset_genres",
        joinColumns = [JoinColumn(name = "image_preset_id")],
        inverseJoinColumns = [JoinColumn(name = "tag_id")],
    )
    val genres: Set<StoryCreationTag> = emptySet(),

    @Column(name = "deactivated_at")
    var deactivatedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

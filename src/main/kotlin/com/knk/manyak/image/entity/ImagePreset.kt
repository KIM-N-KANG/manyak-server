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
 * 팀 제작 이미지 카탈로그. 기존 스토리의 프리셋 키와 계획된 배경 기능을 위해 유지한다(스펙 §4-3-9).
 * 새 스토리에는 프리셋 표지를 연결하지 않으며, 기존 키의 노출 폴백은 비활성 여부와 무관하다.
 *
 * `imageKey`는 불변이고 이미지 교체는 새 키 발급이다 — 저장된 지난 턴이 언제 봐도 같아야 하기 때문이다.
 * 서빙 URL은 저장하지 않고 [com.knk.manyak.image.service.ImageUrlResolver]가 조합한다.
 *
 * 카탈로그 행은 삭제하지 않는다. [deactivatedAt]은 계획된 배경 기능의 후보 제외와
 * 지난 턴 `images[]` 재구성 시 확정 시각 기준 판정에 사용한다(KNK-544). 배경 마커는 아직 구현하지 않았다.
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

    // 카탈로그의 장르는 복수(썸네일 최대 3개)이며 GENRE 태그 마스터를 참조한다.
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

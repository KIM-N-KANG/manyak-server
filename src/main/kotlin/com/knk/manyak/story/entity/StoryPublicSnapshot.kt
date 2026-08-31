package com.knk.manyak.story.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 스토리가 **마지막으로 공개(PUBLISHED∧PUBLIC)였던 시점**의 표시·생성 재료(KNK-1065).
 *
 * `stories.last_public_snapshot`(jsonb)에 통째로 담긴다. 읽을 수 없는 스토리를 참조하는 채팅 경로
 * (서재·이용내역·상세·공유·AI 턴 요청 조립)는 스토리의 현재 값 대신 이 스냅샷을 쓴다 — 제작자가 스토리를
 * 감추고 뜯어고치는 중이면 그 개작이 이미 채팅을 시작한 독자에게, 또 생성 결과를 통해 새어 나가기 때문이다.
 *
 * 채팅별이 아니라 **스토리별**이라 "마지막으로 보이던 값"이 하나로 유지된다. KNK-1059의 채팅별 스냅샷은
 * 채팅 생성 시점에 박혀, 공개 상태에서 이뤄진 밸런스 패치를 비공개 전환 시 되돌려 보여주는 문제가 있었다.
 *
 * **모든 필드에 기본값이 있고 미지 필드를 무시한다.** 스키마가 나중에 늘어도 옛 JSON을 그대로 읽을 수 있어야
 * 하기 때문이다 — 이 값은 마이그레이션 대상이 아니라 과거 기록이다.
 *
 * 목록의 **순서가 곧 표시 순서**다(시작 설정 id 오름차순, 추천 입력 input_order, 엔딩·주요 사건 sort_order).
 * 그래서 sort_order를 따로 담지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StoryPublicSnapshot(
    val title: String = "",
    val thumbnailImageKey: String? = null,
    val genre: String? = null,
    val storySettings: StorySettingsSnapshot = StorySettingsSnapshot(),
    val startSettings: List<StartSettingSnapshot> = emptyList(),
    val mainEvents: List<MainEventSnapshot> = emptyList(),
) {
    /** [chatStartSettingId]가 가리키는 시작 설정. 시작 설정이 지워져 참조가 끊긴 채팅은 null이다. */
    fun startSettingOf(chatStartSettingId: Long?): StartSettingSnapshot? =
        chatStartSettingId?.let { id -> startSettings.firstOrNull { it.id == id } }

    /** 스냅샷이 담은 모든 시작 설정의 엔딩 id→이름. 도달 엔딩 이름을 되찾는 데 쓴다. */
    fun endingNameById(): Map<Long, String> =
        startSettings.flatMap { it.endings }.associate { it.id to it.name }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class StorySettingsSnapshot(
    val worldSetting: String? = null,
    val characterSetting: String? = null,
    val userRoleSetting: String? = null,
    val ruleSetting: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StartSettingSnapshot(
    // 채팅이 참조하는 story_start_settings.id. 외부로 나가지 않는 서버 내부 JSON이라 순차 PK를 그대로 쓴다
    // (외부 노출 식별자는 public_id라는 규칙의 대상이 아니다). 채팅의 start_setting_id로 곧장 찾기 위해서다.
    val id: Long = 0,
    val name: String = "",
    val prologue: String? = null,
    val startSituation: String? = null,
    val suggestedInputs: List<String> = emptyList(),
    val endings: List<EndingSnapshot> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EndingSnapshot(
    val id: Long = 0,
    val name: String = "",
    val minTurns: Int = 0,
    val achievementCondition: String = "",
    val epilogue: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MainEventSnapshot(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val keySentence: String = "",
)

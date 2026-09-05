package com.knk.manyak.image.service

/**
 * 사용자 업로드 이미지의 검수 상태(KNK-1126, 스펙 §4-3-8 검수 게이트).
 *
 * 지금은 저장 기본값이 [APPROVED]라 업로드가 즉시 반영되고 신고로 대응한다. 자동 검수(Rekognition)나 공개
 * 스토리 검수(KNK-1160~1162)를 도입할 때 기본값을 [PENDING]으로 바꾸고 승인 경로만 붙이면 되며,
 * **노출 코드는 손대지 않는다** — 판정이 [ImageModeration] 한 곳에 있기 때문이다.
 */
enum class ImageModerationStatus {
    APPROVED,
    PENDING,
    REJECTED,
}

/**
 * 노출·AI 전달 게이트. 상세·목록·채팅 카드의 `thumbnailUrl`, 상세 `characters[].imageUrl`, 채팅 요청
 * `character_images[]`가 **모두 이 한 판정을 지난다**. 소유자의 편집 폼만 예외로 상태와 함께 원본을 보여준다.
 */
object ImageModeration {

    /**
     * [status]가 null이면 보인다 — 검수 상태를 남기지 않는 경로(공개 스냅샷 `story_public_snapshots`)가 있고,
     * 그 스냅샷은 공개였던 시점의 표시값이라 여기서 새로 막을 근거가 없다.
     */
    fun isVisible(status: ImageModerationStatus?): Boolean =
        status == null || status == ImageModerationStatus.APPROVED
}

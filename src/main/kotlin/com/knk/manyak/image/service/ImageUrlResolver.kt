package com.knk.manyak.image.service

import com.knk.manyak.image.entity.ImagePresetType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * `imageKey` → 서빙 URL 변환. DB 정본은 `imageKey`이고 URL 조합은 백엔드가 소유한다(스펙 §4-3-9).
 *
 * Phase 1 자산은 전수 PNG이므로 확장자는 `.png` 고정이며 카탈로그에 저장하지 않는다.
 * base URL이 비어 있으면(인프라 미구성) null을 돌려 프론트엔드가 placeholder를 그리게 둔다 —
 * 이미지 한 장 때문에 스토리 조회가 실패해서는 안 된다.
 */
@Component
class ImageUrlResolver(
    @Value("\${manyak.asset.image-base-url:}") private val baseUrl: String,
) {
    fun urlFor(imageKey: String?, type: ImagePresetType): String? {
        if (imageKey.isNullOrBlank() || baseUrl.isBlank()) {
            return null
        }
        return "${baseUrl.trimEnd('/')}/${type.prefix}/$imageKey.png"
    }

    /**
     * 썸네일의 축소 변형(`_sm`) URL. 목록·채팅 카드가 쓴다(스펙 §4-3-9 반응형 변형, KNK-548).
     *
     * `_sm`은 저장하지 않고 URL 조합 시 파생한다(`imageKey` 불변). 변형은 썸네일에만 존재하며
     * 배경·캐릭터는 단일 원본뿐이라 이 함수를 쓰지 않는다.
     *
     * `_sm` 객체 자체의 생성·업로드는 인프라 소유다(`manyak-terraform`, KNK-548). 서버 레포의
     * `scripts/image-presets.rename.tsv`는 원본 객체만 담으므로, 그 파일만으로 버킷을 재구성하면
     * 축소본이 빠져 목록·채팅 카드가 404가 된다.
     */
    fun thumbnailSmUrlFor(imageKey: String?): String? =
        urlFor(imageKey?.let { "${it}$SM_SUFFIX" }, ImagePresetType.THUMBNAIL)

    /**
     * 상세용 표지 URL. 컴파일이 생성한 표지가 있으면 그것을, 없으면 프리셋 키로 조합한 URL을 쓴다(KNK-1069).
     *
     * **2단 폴백을 여기 한 곳에만 둔다.** 생성 성공이어도 프리셋 연결(`thumbnail_image_key`)은 지우지 않으므로
     * 두 값이 함께 존재하는 게 정상이고, 어느 쪽을 보여줄지 판정은 호출부마다 흩어지면 곧 어긋난다.
     * 공백 문자열은 값이 없는 것으로 본다(빈 URL이 저장돼도 프리셋으로 떨어지게).
     */
    fun thumbnailUrlFor(generatedUrl: String?, imageKey: String?): String? =
        generatedUrl?.takeIf { it.isNotBlank() } ?: urlFor(imageKey, ImagePresetType.THUMBNAIL)

    /**
     * 목록·카드용 표지 URL. 생성 표지가 있으면 **원본 URL을 그대로** 쓰고, 없으면 프리셋의 `_sm` 변형을 쓴다.
     *
     * ponytail: 생성 표지는 축소본을 만들지 않는다 — 목록 카드가 768x1024 원본을 받는다. 카드 무게가 문제가
     * 되면 업로드 시 `_sm` 파생본을 함께 올리고(또는 CDN 이미지 리사이즈를 붙이고) 여기서 그 URL을 쓴다.
     */
    fun thumbnailSmUrlFor(generatedUrl: String?, imageKey: String?): String? =
        generatedUrl?.takeIf { it.isNotBlank() } ?: thumbnailSmUrlFor(imageKey)

    /**
     * **공개 노출용** 표지 URL(KNK-1126 검수 게이트). 업로드·생성 표지는 [ImageModeration.isVisible]을 통과할
     * 때만 쓰고, 아니면 프리셋으로 떨어진다 — 검수에 걸린 이미지가 상세·목록·채팅 카드에 나가지 않게 한다.
     *
     * 소유자의 편집 폼은 이 게이트를 쓰지 않는다([thumbnailUrlFor]) — 본인이 올린 이미지는 상태와 함께
     * 그대로 보여야 무엇이 걸렸는지 알 수 있다.
     */
    fun visibleThumbnailUrlFor(uploadedUrl: String?, imageKey: String?, status: ImageModerationStatus?): String? =
        thumbnailUrlFor(uploadedUrl.takeIf { ImageModeration.isVisible(status) }, imageKey)

    /** 목록·카드용. [visibleThumbnailUrlFor]와 같은 게이트를 지난다. */
    fun visibleThumbnailSmUrlFor(uploadedUrl: String?, imageKey: String?, status: ImageModerationStatus?): String? =
        thumbnailSmUrlFor(uploadedUrl.takeIf { ImageModeration.isVisible(status) }, imageKey)

    private companion object {
        const val SM_SUFFIX = "_sm"
    }
}

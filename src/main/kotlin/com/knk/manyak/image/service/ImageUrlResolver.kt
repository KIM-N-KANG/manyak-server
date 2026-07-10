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
     */
    fun thumbnailSmUrlFor(imageKey: String?): String? =
        urlFor(imageKey?.let { "${it}$SM_SUFFIX" }, ImagePresetType.THUMBNAIL)

    private companion object {
        const val SM_SUFFIX = "_sm"
    }
}

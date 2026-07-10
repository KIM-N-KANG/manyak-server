package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.ChatImageResponse
import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.image.service.ImageUrlResolver
import org.springframework.stereotype.Component

/**
 * AI 본문에 박힌 이미지 마커를 검증해 `images[]`를 만든다(스펙 §4-3-9 검증·저장·스트리밍).
 *
 * 본문은 손대지 않는다 — 마커가 포함된 채로 저장·중계하고, 걸러진 마커는 프론트엔드가 텍스트째 숨긴다.
 * 백엔드 검증은 세 겹이다: 카탈로그 키 필터 · 비활성 제외 · 타입별 턴당 1장 상한(등장 순서상 첫 장만).
 * AI 서버도 같은 규칙을 1차로 담보하므로 이중 방어다.
 */
@Component
class ChatImageBundler(
    private val imagePresetRepository: ImagePresetRepository,
    private val imageUrlResolver: ImageUrlResolver,
) {
    fun bundle(aiOutput: String): List<ChatImageResponse> {
        val markedKeys = MARKER_PATTERN.findAll(aiOutput).map { it.groupValues[1] }.toList()
        if (markedKeys.isEmpty()) {
            return emptyList()
        }

        // 카탈로그에 없거나 비활성인 키는 조회 결과에서 빠진다(한 번의 IN 조회).
        val activePresets = imagePresetRepository
            .findByImageKeyInAndDeactivatedAtIsNull(markedKeys.toSet())
            .associateBy { it.imageKey }

        val takenTypes = mutableSetOf<ImagePresetType>()
        return markedKeys.mapNotNull { key ->
            val preset = activePresets[key] ?: return@mapNotNull null
            // 채팅 본문에 실리는 타입은 배경·캐릭터뿐이다. 썸네일 키 마커는 무효다.
            if (preset.type == ImagePresetType.THUMBNAIL) return@mapNotNull null
            // 타입별 턴당 1장 — 등장 순서상 첫 장만 남기고 나머지는 버린다(중복 키도 여기서 걸린다).
            if (!takenTypes.add(preset.type)) return@mapNotNull null
            val url = imageUrlResolver.urlFor(key, preset.type) ?: return@mapNotNull null
            ChatImageResponse(imageKey = key, type = preset.type, url = url)
        }
    }

    companion object {
        /** `imageKey` 문자 집합이 좁아(`[a-z0-9_]{1,64}`) 추출 정규식이 안전하다. */
        val MARKER_PATTERN = Regex("""\[\[image:([a-z0-9_]{1,64})]]""")
    }
}

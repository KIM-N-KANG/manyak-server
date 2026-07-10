package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.ChatImageResponse
import com.knk.manyak.image.entity.ImagePreset
import com.knk.manyak.image.entity.ImagePresetType
import com.knk.manyak.image.repository.ImagePresetRepository
import com.knk.manyak.image.service.ImageUrlResolver
import org.springframework.stereotype.Component
import java.time.Instant

/** 지난 턴 재구성 입력 — 본문과 그 본문이 확정된 시각. */
data class ConfirmedTurnContent(
    val turnId: Long,
    val aiOutput: String,
    val contentConfirmedAt: Instant,
)

/**
 * AI 본문에 박힌 이미지 마커를 검증해 `images[]`를 만든다(스펙 §4-3-9 검증·저장·스트리밍).
 *
 * 본문은 손대지 않는다 — 마커가 포함된 채로 저장·중계하고, 걸러진 마커는 프론트엔드가 텍스트째 숨긴다.
 * 백엔드 검증은 세 겹이다: 카탈로그 키 필터 · 비활성 제외 · 타입별 턴당 1장 상한(등장 순서상 첫 장만).
 * AI 서버도 같은 규칙을 1차로 담보하므로 이중 방어다.
 *
 * `images[]`는 저장하지 않는다. 상세 조회는 [reconstruct]로 다시 만들되 **그 턴의 확정 시각 시점 카탈로그
 * 상태**를 보므로 `completed`가 내려준 결과와 같아진다.
 */
@Component
class ChatImageBundler(
    private val imagePresetRepository: ImagePresetRepository,
    private val imageUrlResolver: ImageUrlResolver,
) {
    /** `completed` 동봉용 — 지금 활성인 프리셋만 유효하다. */
    fun bundle(aiOutput: String): List<ChatImageResponse> {
        val markedKeys = extractKeys(aiOutput)
        if (markedKeys.isEmpty()) {
            return emptyList()
        }
        val presetsByKey = imagePresetRepository
            .findByImageKeyInAndDeactivatedAtIsNull(markedKeys.toSet())
            .associateBy { it.imageKey }
        return select(markedKeys) { presetsByKey[it] }
    }

    /**
     * 상세 조회용 — 턴마다 확정 시각 기준으로 재구성한다. 카탈로그는 한 번의 IN 조회로 모아 온다(N+1 없음).
     *
     * 포함 조건: `등록 시각 <= 확정 시각 AND (deactivated_at IS NULL OR deactivated_at > 확정 시각)`.
     * 앞은 확정 이후 등재된 키가 소급 유효해지는 것을, 뒤는 확정 시점에 비활성이던 키가 살아나는 것을 막는다.
     */
    fun reconstruct(turns: List<ConfirmedTurnContent>): Map<Long, List<ChatImageResponse>> {
        val keysByTurn = turns.associate { it.turnId to extractKeys(it.aiOutput) }
        val allKeys = keysByTurn.values.flatten().toSet()
        if (allKeys.isEmpty()) {
            return emptyMap()
        }
        val presetsByKey = imagePresetRepository.findByImageKeyIn(allKeys).associateBy { it.imageKey }

        return turns.associate { turn ->
            val images = select(keysByTurn.getValue(turn.turnId)) { key ->
                presetsByKey[key]?.takeIf { it.wasActiveAt(turn.contentConfirmedAt) }
            }
            turn.turnId to images
        }
    }

    private fun extractKeys(aiOutput: String): List<String> =
        MARKER_PATTERN.findAll(aiOutput).map { it.groupValues[1] }.toList()

    /** 유효한 프리셋만 남기고 타입별 첫 장만 취한다. 중복 키도 여기서 걸린다. */
    private fun select(markedKeys: List<String>, lookup: (String) -> ImagePreset?): List<ChatImageResponse> {
        if (markedKeys.isEmpty()) {
            return emptyList()
        }
        val takenTypes = mutableSetOf<ImagePresetType>()
        return markedKeys.mapNotNull { key ->
            val preset = lookup(key) ?: return@mapNotNull null
            // 채팅 본문에 실리는 타입은 배경·캐릭터뿐이다. 썸네일 키 마커는 무효다.
            if (preset.type == ImagePresetType.THUMBNAIL) return@mapNotNull null
            if (!takenTypes.add(preset.type)) return@mapNotNull null
            val url = imageUrlResolver.urlFor(key, preset.type) ?: return@mapNotNull null
            ChatImageResponse(imageKey = key, type = preset.type, url = url)
        }
    }

    // 등록 시각 컷오프(확정 이후 등재된 키의 소급 유효화 차단) + 비활성 시각 컷오프(확정 시점 활성 여부 재현).
    // deactivatedAt이 null이면 계속 활성, 확정 시각보다 뒤면 그 시점엔 활성, 같거나 앞이면 그 시점엔 비활성이다.
    private fun ImagePreset.wasActiveAt(at: Instant): Boolean =
        !createdAt.isAfter(at) && deactivatedAt?.isAfter(at) != false

    companion object {
        /** `imageKey` 문자 집합이 좁아(`[a-z0-9_]{1,64}`) 추출 정규식이 안전하다. */
        val MARKER_PATTERN = Regex("""\[\[image:([a-z0-9_]{1,64})]]""")
    }
}

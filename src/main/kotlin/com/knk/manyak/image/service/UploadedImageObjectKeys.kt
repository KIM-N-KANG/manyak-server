package com.knk.manyak.image.service

import java.util.UUID

/**
 * 사용자 업로드 이미지의 객체 키(KNK-1126, 스펙 §4-3-8).
 *
 * `{prefix}/{storyPublicId}/{uuid}.{ext}`다. **키는 서버가 정한다** — 클라이언트가 정하면 남의 스토리 경로나
 * 프리셋 자산 키를 덮어쓸 수 있다. 연결 시점에도 키가 그 스토리의 prefix 아래인지 다시 확인한다.
 *
 * 표지가 `thumbnails/` 아래인 이유는 웹이 원격 이미지를 `cdn.manyak.app`의 `thumbnails` 경로만 허용하기 때문이다
 * (스펙 §4-3-9). 생성 자산(`.../generated/`)과 경로를 나눠 terraform 쓰기 권한도 따로 준다(KNK-1200).
 */
object UploadedImageObjectKeys {

    const val KEY_PREFIX_COVER = "thumbnails/uploaded"
    const val KEY_PREFIX_CHARACTER = "characters/uploaded"

    /** 허용 형식과 확장자. presign·연결 양쪽이 이 표를 쓴다. */
    val EXTENSION_BY_CONTENT_TYPE = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
    )

    /** 업로드 상한 5MB. presign 요청 검증과 연결 시 HEAD 재검증이 같은 값을 쓴다. */
    const val MAX_CONTENT_LENGTH = 5L * 1024 * 1024

    fun prefixOf(kind: UploadedImageKind, storyPublicId: UUID): String =
        "${kind.keyPrefix}/$storyPublicId"

    fun newObjectKey(kind: UploadedImageKind, storyPublicId: UUID, contentType: String): String {
        val extension = EXTENSION_BY_CONTENT_TYPE.getValue(contentType)
        return "${prefixOf(kind, storyPublicId)}/${UUID.randomUUID()}.$extension"
    }
}

/** 업로드 대상. 객체 키 prefix가 갈린다. */
enum class UploadedImageKind(val keyPrefix: String) {
    COVER(UploadedImageObjectKeys.KEY_PREFIX_COVER),
    CHARACTER(UploadedImageObjectKeys.KEY_PREFIX_CHARACTER),
}

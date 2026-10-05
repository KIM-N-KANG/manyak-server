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

    /**
     * 등록 전 업로드(일반 제작, KNK-1390)의 스코프 세그먼트. 스토리가 아직 없어 스토리 대신 **사용자**
     * 공개 식별자로 소유를 가른다. UUID와 겹치지 않는 고정 문자열이라 스토리 경로와 섞이지 않는다.
     */
    const val DRAFT_SEGMENT = "drafts"

    fun prefixOf(kind: UploadedImageKind, storyPublicId: UUID): String =
        "${kind.keyPrefix}/$storyPublicId"

    /** 등록 전 업로드 경로. `{prefix}/drafts/{userPublicId}`이며 등록 요청이 같은 규칙으로 소유를 확인한다. */
    fun draftPrefixOf(kind: UploadedImageKind, userPublicId: UUID): String =
        "${kind.keyPrefix}/$DRAFT_SEGMENT/$userPublicId"

    fun newObjectKey(kind: UploadedImageKind, storyPublicId: UUID, contentType: String): String =
        "${prefixOf(kind, storyPublicId)}/${newFileName(contentType)}"

    fun newDraftObjectKey(kind: UploadedImageKind, userPublicId: UUID, contentType: String): String =
        "${draftPrefixOf(kind, userPublicId)}/${newFileName(contentType)}"

    private fun newFileName(contentType: String): String =
        "${UUID.randomUUID()}.${EXTENSION_BY_CONTENT_TYPE.getValue(contentType)}"
}

/** 업로드 대상. 객체 키 prefix가 갈린다. */
enum class UploadedImageKind(val keyPrefix: String) {
    COVER(UploadedImageObjectKeys.KEY_PREFIX_COVER),
    CHARACTER(UploadedImageObjectKeys.KEY_PREFIX_CHARACTER),
}

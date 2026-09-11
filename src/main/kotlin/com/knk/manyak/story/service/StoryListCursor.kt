package com.knk.manyak.story.service

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Base64
import java.util.UUID

/**
 * 공개 스토리 목록 정렬(KNK-149). [prefix]는 커서에 박아 정렬이 다른 커서를 섞어 쓰는 것을 막는다 —
 * 인기 정렬 커서의 "정렬값"은 좋아요 수라 최신 정렬에 그대로 넣으면 엉뚱한 시각으로 해석된다.
 */
enum class StoryListSort(val parameter: String, val prefix: String) {
    LATEST("latest", "l"),
    POPULAR("popular", "p"),
    ;

    companion object {
        fun from(value: String): StoryListSort =
            entries.firstOrNull { it.parameter == value }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 정렬입니다: $value")
    }
}

/**
 * keyset 커서(KNK-149). 정렬 1차 키의 값과 2차 키 `public_id`를 담는다.
 *
 * 2차 키가 내부 PK가 아니라 `public_id`인 이유는 외부 노출 식별자 규칙 때문이다(순차 PK를 API에 실으면 IDOR).
 * UUID는 랜덤이지만 안정적이라 동률 구간의 순서를 결정적으로 만든다.
 *
 * 1차 키 값([sortValue])은 정렬별로 뜻이 다르다 — 최신순은 `createdAt`의 **epoch nanos**, 인기순은 좋아요 수.
 * millis가 아니라 nanos인 이유는 PostgreSQL `timestamptz`가 마이크로초까지 담기 때문이다. 밀리초로 자르면
 * 같은 밀리초 안의 뒤쪽 행이 `createdAt < 커서`에도 `= 커서`에도 걸리지 않아 페이지 경계에서 통째로 사라진다.
 * offset이 아니라 keyset이라 페이지 사이에 행이 끼어들어도 중복·누락이 없다.
 */
data class StoryListCursor(
    val sortValue: Long,
    val publicId: UUID,
) {
    /** `"<정렬 접두>:<정렬값>:<publicId>"`를 Base64URL(패딩 없음)로 감싼다. 내부 PK는 싣지 않는다. */
    fun encode(sort: StoryListSort): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${sort.prefix}:$sortValue:$publicId".toByteArray())

    companion object {
        /** 디코드 실패·형식 오류·정렬 종류 불일치는 전부 400이다(서버 오류가 아니라 클라이언트가 만든 값). */
        fun decode(raw: String, sort: StoryListSort): StoryListCursor {
            val parts = runCatching { String(Base64.getUrlDecoder().decode(raw)).split(":") }
                .getOrElse { throw badCursor() }
            if (parts.size != 3 || parts[0] != sort.prefix) {
                throw badCursor()
            }
            val sortValue = parts[1].toLongOrNull() ?: throw badCursor()
            val publicId = runCatching { UUID.fromString(parts[2]) }.getOrElse { throw badCursor() }
            return StoryListCursor(sortValue, publicId)
        }

        private fun badCursor() = ResponseStatusException(HttpStatus.BAD_REQUEST, "커서가 올바르지 않습니다.")
    }
}

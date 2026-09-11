package com.knk.manyak.user.service

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * 닉네임 규칙(KNK-1147, 정책 KNK-1146).
 *
 * - 앞뒤 공백을 지운 뒤 2~20자.
 * - 허용 문자는 한글 완성형(가-힣)·영문·숫자·스페이스뿐이다. 자모 단독(ㄱ, ㅏ)·특수문자·이모지는 거부한다 —
 *   자모는 완성형 범위 밖이라 이 화이트리스트 하나로 함께 걸린다.
 * - 연속 공백은 거부한다(보이지 않는 차이로 비슷한 닉네임을 만드는 경로).
 * - 변경 주기 제한·금칙어 필터는 두지 않는다(KNK-1146 결정).
 */
private val ALLOWED_NICKNAME = Regex("^[가-힣a-zA-Z0-9 ]+$")

private const val MIN_NICKNAME_LENGTH = 2
private const val MAX_NICKNAME_LENGTH = 20

/**
 * 유일 판정에 쓰는 정규화 키: 소문자로 낮추고 공백을 전부 지운다.
 *
 * **V75의 유니크 인덱스식(`replace(lower(nickname), ' ', '')`)과 같은 식이어야 한다.** 앱이 다른 식으로
 * 판정하면 사전 조회는 통과하는데 저장이 유니크 위반으로 깨진다(또는 그 반대).
 */
fun nicknameKeyOf(nickname: String): String = nickname.lowercase().replace(" ", "")

/** 앞뒤 공백을 지운 닉네임을 돌려준다. 규칙을 어기면 400. */
fun requireValidNickname(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length !in MIN_NICKNAME_LENGTH..MAX_NICKNAME_LENGTH) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "닉네임은 앞뒤 공백을 제외하고 ${MIN_NICKNAME_LENGTH}~${MAX_NICKNAME_LENGTH}자여야 합니다.",
        )
    }
    if (!ALLOWED_NICKNAME.matches(trimmed)) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "닉네임에는 한글·영문·숫자·공백만 쓸 수 있습니다.",
        )
    }
    if (trimmed.contains("  ")) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "닉네임에 공백을 연속으로 쓸 수 없습니다.")
    }
    return trimmed
}

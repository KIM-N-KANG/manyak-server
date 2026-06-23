package com.knk.manyak.global.observability

/**
 * 길이를 구간 라벨로 변환한다(AN-3 §8). 채팅 메시지 같은 사용자 원문을 로그에 남기지 않고
 * 길이 구간만 message_length_bucket으로 기록하기 위한 유틸.
 */
object LengthBuckets {
    fun of(length: Int): String = when {
        length <= 0 -> "0"
        length <= 20 -> "1-20"
        length <= 100 -> "21-100"
        length <= 300 -> "101-300"
        length <= 1000 -> "301-1000"
        else -> "1001+"
    }
}

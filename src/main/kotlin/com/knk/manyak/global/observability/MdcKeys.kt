package com.knk.manyak.global.observability

/**
 * 구조화 로그(AN-3 §4)에서 그대로 출력될 MDC 키.
 * 스펙 JSON 필드명과 1:1로 맞춰, KNK-217 JSON 인코더가 추가 매핑 없이 그대로 내보내게 한다.
 */
object MdcKeys {
    const val REQUEST_ID = "request_id"
    const val SESSION_ID = "session_id"
    const val DEVICE_ID_HASH = "device_id_hash"
}

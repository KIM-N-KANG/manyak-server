package com.knk.manyak.story.service

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * 한 시작 설정 안에서 엔딩 이름이 유니크한지 강제한다(중복이면 400).
 *
 * 엔딩은 이름으로 식별하며(KNK-462), 도달 엔딩(reachedEndings)도 이름으로 노출한다(KNK-523).
 * 같은 시작 설정에 같은 이름 엔딩이 둘 이상이면 어느 쪽이 도달됐는지 표시가 모호해지므로 제작·수정 단계에서 막는다.
 * (저장 자체는 ending id 기준이라 무모호하지만, 이름 기반 노출의 상관을 위해 이름 유니크를 보장한다.)
 */
fun requireDistinctEndingNames(names: List<String>) {
    if (names.size != names.toSet().size) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "엔딩 이름은 시작 설정 내에서 중복될 수 없습니다.")
    }
}

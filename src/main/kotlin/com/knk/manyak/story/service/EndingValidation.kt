package com.knk.manyak.story.service

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * 이름으로 식별하는 저작 요소(엔딩·주요 사건)의 이름 유니크를 강제한다(중복이면 400).
 *
 * 채팅 런타임이 도달 엔딩·완결/목표 주요 사건을 **이름으로** 주고받고(reachedEndings·occurred/target,
 * KNK-462·523), 백엔드는 `findFirstBy...Name`으로 id를 해소한다. 같은 스코프에 같은 이름이 둘 이상이면
 * 어느 쪽이 지목됐는지 표시·매칭이 모호해지므로 제작·수정 단계에서 막는다(저장은 id 기준이라 무모호).
 */

/** 엔딩 이름 유니크(시작 설정 스코프). */
fun requireDistinctEndingNames(names: List<String>) =
    requireDistinctNames(names, "엔딩 이름은 시작 설정 내에서 중복될 수 없습니다.")

/** 주요 사건 이름 유니크(스토리 스코프). */
fun requireDistinctMainEventNames(names: List<String>) =
    requireDistinctNames(names, "주요 사건 이름은 스토리 내에서 중복될 수 없습니다.")

private fun requireDistinctNames(names: List<String>, message: String) {
    if (names.size != names.toSet().size) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
    }
}

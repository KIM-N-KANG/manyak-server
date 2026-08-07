package com.knk.manyak.chat.service

import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.global.error.CodedResponseStatusException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * KNK-811 / Codex P2: 스트림 개시 전 실패를 `rejected`(4xx 거부)와 `failure`로 가르는 분류표를 고정한다.
 *
 * **이 구분이 깨지면 운영 장애가 조용히 사라진다.** 대시보드·알림은 `rejected`를 실패 축에서 제외하므로,
 * Redis·DB 장애처럼 5xx로 나가는 실패가 `rejected`로 분류되면 실패 신호에 잡히지 않는다.
 *
 * Spring 컨텍스트를 띄우지 않는 순수 단위 테스트라 컨텍스트 캐시 예산에 영향이 없다.
 */
class ChatTurnRejectionClassificationTest {

    @Test
    fun `크레딧 부족은 ResponseStatusException이 아니어도 rejected다`() {
        // 컨트롤러가 402로 변환하는 도메인 예외라 상태 코드만 보면 놓친다.
        val exception = InsufficientCreditException(userId = 1L, required = 10, balance = 0)

        assertThat(isChatTurnClientRejection(exception)).isTrue()
    }

    @Test
    fun `게스트 한도 소진 402는 rejected다`() {
        val exception = CodedResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "GUEST_LIMIT_EXCEEDED", "체험 한도를 모두 사용했습니다.")

        assertThat(isChatTurnClientRejection(exception)).isTrue()
    }

    @Test
    fun `device 헤더 누락 400은 rejected다`() {
        val exception = ResponseStatusException(HttpStatus.BAD_REQUEST, "디바이스 식별자가 필요합니다.")

        assertThat(isChatTurnClientRejection(exception)).isTrue()
    }

    @Test
    fun `Redis·DB 장애는 rejected가 아니라 failure다`() {
        // 예약·차감이 인프라 문제로 깨지면 5xx로 나간다. rejected로 세면 실패 알림에서 사라진다.
        assertThat(isChatTurnClientRejection(QueryTimeoutException("Redis 응답 없음"))).isFalse()
        assertThat(isChatTurnClientRejection(IllegalStateException("커넥션 획득 실패"))).isFalse()
    }

    @Test
    fun `5xx ResponseStatusException은 rejected가 아니다`() {
        val exception = ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 서버 오류")

        assertThat(isChatTurnClientRejection(exception)).isFalse()
    }
}

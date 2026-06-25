package com.knk.manyak.global.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class MdcTaskDecoratorTests {

    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `데코레이트 시점의 MDC를 실행 스레드에 복사한다`() {
        MDC.put("request_id", "req_x")
        var captured: String? = "UNSET"
        val decorated = MdcTaskDecorator().decorate { captured = MDC.get("request_id") }

        // 워커 스레드처럼 MDC가 비어 있는 상태를 흉내 낸 뒤 실행한다.
        MDC.clear()
        decorated.run()

        assertThat(captured).isEqualTo("req_x")
    }

    @Test
    fun `실행 후 원래 컨텍스트로 되돌려 누수가 없다`() {
        val decorated = MdcTaskDecorator().decorate { MDC.put("request_id", "during") }

        decorated.run()

        assertThat(MDC.get("request_id")).isNull()
    }
}

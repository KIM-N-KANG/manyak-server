package com.knk.manyak.global.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.MDC
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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

    @Test
    fun `데코레이트 시점의 관측 스코프를 실행 스레드에 전파한다`() {
        // 추적이 켜지면 관측(스팬)도 스레드 로컬이라 MDC처럼 복사해야 워커의 하위 스팬이 같은 트레이스에 붙는다.
        val registry = ObservationRegistry.create()
        val parent = Observation.start("parent", registry)
        val seen = CompletableFuture<Observation?>()
        val decorated = parent.openScope().use {
            MdcTaskDecorator().decorate { seen.complete(registry.currentObservation) }
        }

        val worker = Thread(decorated).apply { start() }
        worker.join(TimeUnit.SECONDS.toMillis(5))

        assertThat(seen.get(5, TimeUnit.SECONDS)).isSameAs(parent)
        assertThat(registry.currentObservation).isNull()
        parent.stop()
    }
}

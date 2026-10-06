package com.knk.manyak.global.config

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class TracingNoiseFilterTests {
    private val predicate = TracingNoiseFilter.predicate()

    @Test
    fun `예약 작업 실행은 관측하지 않는다`() {
        assertThat(predicate.test("tasks.scheduled.execution", Observation.Context())).isFalse()
    }

    @Test
    fun `actuator 요청은 관측하지 않고 API 요청은 관측한다`() {
        assertThat(predicate.test("http.server.requests", serverRequest("/actuator/health"))).isFalse()
        assertThat(predicate.test("http.server.requests", serverRequest("/actuator/prometheus"))).isFalse()
        assertThat(predicate.test("http.server.requests", serverRequest("/api/v1/stories"))).isTrue()
    }

    @Test
    fun `요청 바깥의 DB와 Redis 호출은 관측하지 않고 요청 안의 호출은 관측한다`() {
        val root = Observation.Context()
        assertThat(predicate.test("jdbc.query", root)).isFalse()
        assertThat(predicate.test("lettuce", root)).isFalse()
        // 걸러진 예약 작업은 NOOP 부모로 남는다. 그 아래 호출도 루트처럼 거른다.
        val underFiltered = Observation.Context().apply { parentObservation = Observation.NOOP }
        assertThat(predicate.test("jdbc.query", underFiltered)).isFalse()
        val registry = ObservationRegistry.create().apply { observationConfig().observationHandler { true } }
        val inRequest = Observation.Context().apply { parentObservation = Observation.start("http.server.requests", registry) }
        assertThat(predicate.test("jdbc.query", inRequest)).isTrue()
        assertThat(predicate.test("lettuce", inRequest)).isTrue()
    }

    @Test
    fun `Spring Security 내부 관측은 하지 않고 그 밖은 그대로 둔다`() {
        assertThat(predicate.test("spring.security.filterchains", Observation.Context())).isFalse()
        assertThat(predicate.test("spring.security.authorizations", Observation.Context())).isFalse()
        assertThat(predicate.test("chat.turn.queue", Observation.Context())).isTrue()
        assertThat(predicate.test("http.client.requests", Observation.Context())).isTrue()
    }

    private fun serverRequest(uri: String) =
        ServerRequestObservationContext(MockHttpServletRequest("GET", uri), MockHttpServletResponse())
}

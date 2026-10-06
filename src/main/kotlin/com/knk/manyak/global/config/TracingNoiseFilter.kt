package com.knk.manyak.global.config

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import net.ttddyy.observation.tracing.QueryContext
import org.springframework.beans.factory.ObjectProvider
import io.micrometer.observation.ObservationPredicate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.observation.ServerRequestObservationContext

/**
 * 트레이스 노이즈를 관측 단계에서 걸러낸다(KNK-1552).
 *
 * 검수 폴러가 1초마다 도는데 `@Scheduled` 실행마다 트레이스가 하나씩 생겨 하루 8만 건이 넘는다. ALB 헬스 체크의
 * actuator 요청도 같은 부류다. 둘 다 장애 진단에 쓸 일이 없어 스팬을 만들지 않는다.
 *
 * 부모 관측을 걸러도 그 안의 JDBC, Redis(Lettuce 관측 이름 `lettuce`) 호출은 루트 스팬으로 살아남는다. 그래서 요청이나 작업 바깥(루트)에서 시작한
 * DB, Redis 관측도 함께 거른다. 요청·워커 안에서 한 호출은 부모가 있어 그대로 남는다. Spring Security 내부 관측
 * (filterchain, authorize)은 요청마다 스팬 셋을 더하지만 진단 가치가 없어 뺀다.
 */
@Configuration(proxyBeanMethods = false)
class TracingNoiseFilter {
    @Bean
    fun tracingNoisePredicate(registries: ObjectProvider<ObservationRegistry>): ObservationPredicate =
        predicate { registries.ifAvailable?.currentObservation }

    companion object {
        private const val SCHEDULED = "tasks.scheduled.execution"
        private const val HTTP_SERVER = "http.server.requests"
        private const val SECURITY_PREFIX = "spring.security."
        private const val JDBC_PREFIX = "jdbc."
        private const val REDIS = "lettuce"

        fun predicate(currentObservation: () -> Observation? = { null }) = ObservationPredicate { name, context ->
            // datasource-micrometer 2.3.0은 QUERY만 켜도 부모에 NOOP connection을 저장한다.
            // 관측 시작 전 현재 요청/작업 부모로 복구한다. 현재 스코프도 NOOP면 아래 루트 규칙으로 거른다.
            if (name == "jdbc.query" && context is QueryContext &&
                (context.parentObservation as? Observation)?.isNoop == true
            ) {
                context.parentObservation = currentObservation()
            }
            when {
                name == SCHEDULED -> false
                name.startsWith(SECURITY_PREFIX) -> false
                name == HTTP_SERVER && context is ServerRequestObservationContext ->
                    context.carrier?.requestURI?.startsWith("/actuator") != true
                // 걸러진 부모(예약 작업)는 스코프만 여는 NOOP 관측(NoopButScopeHandlingObservation)으로 남아 자식의 부모 자리에
                // 들어온다. 부모가 없거나 NOOP이면 루트로 본다. 걸러진 jdbc.connection 아래의 query, result-set도 같은 규칙으로 걸러진다.
                (name.startsWith(JDBC_PREFIX) || name == REDIS) && context.parentObservation.let { it == null || (it is Observation && it.isNoop) } -> false
                else -> true
            }
        }
    }
}

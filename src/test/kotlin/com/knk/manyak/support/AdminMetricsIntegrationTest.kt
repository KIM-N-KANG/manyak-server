package com.knk.manyak.support

import com.knk.manyak.admin.analytics.MutableAnalyticsClock
import com.knk.manyak.admin.infra.InfraDatabaseRepository
import com.knk.manyak.admin.play.PlayReportsSource
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.ecs.EcsClient
import java.time.Instant

/**
 * 관리자 DB 지표 조합(A, KNK-2053). 고정 시각으로 집계를 검증하는 관리자 지표·인프라·설치 지표 테스트가 공유한다.
 *
 * - 시계는 [MutableAnalyticsClock] 하나다. 예전에는 클래스마다 고정 시각이 다른 시계 설정을 `@Import`해 컨텍스트가
 *   갈렸다. 이제 각 클래스가 [clockAt]으로 원래 시각을 밝히고, 테스트마다 그 시각으로 되돌린다.
 * - 집계 제외 회원은 클래스별 값의 합집합이다. 각 클래스가 명시 id(900001~900003·987654321·987654322)로 만든
 *   회원만 제외 대상이 되고, 다른 클래스는 그 id로 회원을 만들지 않으므로 클래스별 결과가 같다.
 * - AWS·Play 클라이언트는 목이다. 인프라·설치 지표 테스트만 그 엔드포인트를 부른다.
 *
 * Amplitude·Langfuse를 부르는 분석 테스트는 여기 넣지 않는다. 이 조합은 두 외부 API를 구성하지 않으며, 구성하면
 * DB 지표 응답도 외부 값을 부르기 시작해 다른 클래스의 결과가 바뀐다.
 */
@Import(AdminMetricsClockConfig::class)
@TestPropertySource(
    properties = [
        "manyak.admin.dashboard.excluded-user-ids=900001,900002,900003,987654321,987654322",
        "manyak.admin.infra.cluster=private-cluster",
        "manyak.admin.infra.services=server=private-server,ai=private-ai,notification=private-notification",
        "manyak.admin.infra.load-balancer=private-alb",
        "manyak.admin.infra.database=private-rds",
        "manyak.admin.infra.cache-clusters=redis-001,redis-002",
        "manyak.admin.infra.domain=private-domain",
        "manyak.admin.infra.account-id=123456789012",
    ],
)
@MockitoBean(types = [PlayReportsSource::class, CloudWatchClient::class, EcsClient::class, InfraDatabaseRepository::class])
abstract class AdminMetricsIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var adminMetricsClock: MutableAnalyticsClock

    /** 이 클래스의 테스트가 시작할 시각. 예전 클래스별 시계 설정의 고정 시각과 같다. */
    protected abstract val clockAt: Instant

    @BeforeEach
    fun resetAdminMetricsClock() {
        adminMetricsClock.at = clockAt
    }
}

@TestConfiguration
class AdminMetricsClockConfig {
    @Bean
    @Primary
    fun adminMetricsClock() = MutableAnalyticsClock(Instant.EPOCH)
}

package com.knk.manyak

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.netty.resources.ConnectionProvider

/**
 * 테스트 HTTP 클라이언트 커넥션 재사용 회귀 가드(KNK-823).
 *
 * RestTestClient는 reactor-netty 기반 요청 팩토리를 쓰고, 그 풀은 JVM 전역이며 기본값이 "유휴 커넥션을
 * 영원히 보관"이다. 서버가 먼저 닫은 커넥션을 풀이 그대로 내주면 POST가 재시도 없이
 * PrematureCloseException으로 터지고, 전체 스위트에서만 간헐로 SSE 통합 테스트가 깨진다.
 * build.gradle.kts가 주입하는 maxIdleTime=0이 그 재사용을 없앤다.
 *
 * 프로퍼티 문자열이 아니라 reactor-netty가 **실제로 읽어 들인 값**을 본다. 프로퍼티 이름을 잘못 적으면
 * 문자열 비교는 통과하지만 풀 설정은 그대로이므로, 여기서 갈리게 한다.
 * (Gradle test 태스크가 주입하므로 반드시 Gradle로 실행한다.)
 */
class TestHttpClientConnectionReuseGuardTests {

    @Test
    fun `테스트 JVM의 reactor-netty 커넥션 풀은 유휴 커넥션을 재사용하지 않는다`() {
        assertThat(ConnectionProvider.DEFAULT_POOL_MAX_IDLE_TIME)
            .`as`("reactor.netty.pool.maxIdleTime=0이 테스트 JVM에 주입돼야 한다(누락 시 KNK-823 플래키 재발).")
            .isZero()
    }
}

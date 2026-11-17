package com.knk.manyak.support

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import redis.embedded.RedisServer
import java.net.ServerSocket

/**
 * 테스트 JVM 세션 시작 시 임베디드 Redis를 한 번 띄우고, 그 포트를 `spring.data.redis.port` 시스템 속성으로 주입한다.
 *
 * 게스트 체험 한도(스펙 §4-3-7)가 Redis 기반이라, `@SpringBootTest` 통합 테스트가 `DatabaseCleaner`의 카운터 정리와
 * 게스트 경로 예약([com.knk.manyak.credit.service.GuestTrialLimitService])에서 실제 Redis에 연결한다. 외부 Redis가 없는
 * CI에서도 이 테스트들이 돌도록, 세션당 임베디드 Redis를 하나 띄워 메인 컨텍스트가 그 포트를 쓰게 한다.
 *
 * 시스템 속성은 application.yml/application-test.yml의 `spring.data.redis.port`(env·기본값)보다 우선하므로,
 * 외부 Redis 유무와 무관하게 임베디드 인스턴스를 사용한다. `@DataRedisTest` 슬라이스가 자체 임베디드 Redis를
 * `@DynamicPropertySource`로 덮어쓰는 것과는 독립적이다(각자 다른 포트).
 *
 * ServiceLoader로 자동 등록된다: `META-INF/services/org.junit.platform.launcher.LauncherSessionListener`.
 * Gradle이 테스트 JVM을 포크하면 포크마다 자체 세션·임베디드 Redis(각기 다른 free 포트)를 갖는다.
 */
class EmbeddedRedisLauncherSessionListener : LauncherSessionListener {

    private var redisServer: RedisServer? = null

    override fun launcherSessionOpened(session: LauncherSession) {
        if (redisServer != null) return
        // 빈 포트를 고른 뒤 Redis가 그 포트를 잡기 전에 다른 포크가 먼저 잡을 수 있다(KNK-2059). 기동이 실패하면 새 포트로 다시 한다.
        // 임베디드 Redis는 기본으로 127.0.0.1에만 연다(lsof로 확인).
        var last: Exception? = null
        repeat(START_ATTEMPTS) {
            val port = ServerSocket(0).use { it.localPort }
            val server = RedisServer.newRedisServer().port(port).build()
            try {
                server.start()
            } catch (e: Exception) {
                last = e
                return@repeat
            }
            System.setProperty("spring.data.redis.port", port.toString())
            redisServer = server
            return
        }
        throw IllegalStateException("임베디드 Redis를 ${START_ATTEMPTS}번 시도했지만 띄우지 못했다", last)
    }

    override fun launcherSessionClosed(session: LauncherSession) {
        redisServer?.stop()
        redisServer = null
    }

    private companion object { const val START_ATTEMPTS = 3 }
}

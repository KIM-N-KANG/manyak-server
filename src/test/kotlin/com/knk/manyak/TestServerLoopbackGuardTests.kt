package com.knk.manyak

import com.knk.manyak.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 테스트 서버의 포트를 다른 소켓이 루프백에 다시 잡아 요청을 가로챌 수 없어야 한다(KNK-2079).
 *
 * macOS는 와일드카드로 열린 포트에도 127.0.0.1 bind를 SO_REUSEADDR로 허용하고, 그때 localhost 요청은 루프백 쪽이 받는다.
 * 테스트 서버를 127.0.0.1에 열면(`application-test.yml`의 `server.address`) 같은 bind가 실패한다. Linux는 와일드카드여도
 * 이 bind를 막으므로 이 가드는 macOS에서만 판별력이 있다.
 */
class TestServerLoopbackGuardTests : IntegrationTest() {
    @Value("\${local.server.port}") private var port: Int = 0

    @Test fun `다른 소켓이 테스트 서버 포트를 루프백에 SO_REUSEADDR로 잡을 수 없다`() {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            assertThatThrownBy { socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)) }
                .isInstanceOf(BindException::class.java)
        }
    }
}

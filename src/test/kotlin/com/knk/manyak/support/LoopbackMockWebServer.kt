package com.knk.manyak.support

import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

/**
 * MockWebServer를 IPv4 루프백(127.0.0.1) 한 주소에 띄운다(KNK-2053 검수).
 *
 * 기본 `start()`는 `localhost`에 바인딩하고 `url()`도 `localhost`로 만든다. 통합 테스트 JVM에는 Tomcat이 와일드카드
 * 주소로 여럿 떠 있어(공용 컨텍스트 23개), 같은 포트 번호가 공존할 때 클라이언트가 `localhost`를 다른 주소 패밀리로
 * 풀면 목 서버가 아니라 Tomcat에 닿을 수 있다. 주소를 숫자로 고정해 그 여지를 없앤다.
 */
fun MockWebServer.startOnLoopback(): MockWebServer = apply { start(InetAddress.getByName(LOOPBACK), 0) }

/** [startOnLoopback]으로 띄운 서버의 URL. `url()`은 호스트명(`localhost`)을 쓰므로 주소를 직접 조합한다. */
fun MockWebServer.loopbackUrl(path: String): String = "http://$LOOPBACK:$port$path"

private const val LOOPBACK = "127.0.0.1"

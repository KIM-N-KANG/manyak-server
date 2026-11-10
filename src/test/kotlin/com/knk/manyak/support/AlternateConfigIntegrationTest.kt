package com.knk.manyak.support

import com.knk.manyak.search.service.StorySearchIndexer
import org.opensearch.client.opensearch.OpenSearchClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * 기능이 꺼진(미구성) 경로를 검증하는 조합(U, KNK-2053). "설정이 비면 503/빈 결과/404" 같은 동작을 한곳에 모은다.
 *
 * - [IntegrationTest]가 켜 둔 공식 계정·내부 API·결제 웹훅 시크릿을 비우고, Langfuse·Amplitude·Play 키도 비우며 springdoc을 끈다.
 *   각 클래스는 자기 기능의 미구성 동작만 보므로 다른 기능이 함께 꺼져 있어도 결과가 같다.
 * - 검색 클라이언트·색인기는 목이다(공식 계정 미구성 검색 카드 검증).
 *
 * 켜면 흐름이 바뀌는 동의 게이트는 [ConsentGateIntegrationTest]로 따로 둔다. 게이트가 켜지면 동의하지 않은 회원의 API가
 * 403이 되고, 동의 테스트는 Swagger 문서도 확인하므로 springdoc을 끈 이 조합과 섞을 수 없다.
 */
@TestPropertySource(
    properties = [
        "manyak.official-user-public-id=",
        "manyak.internal.shared-secret=",
        "manyak.payment.groble.webhook-secret=",
        "manyak.admin.langfuse.host=",
        "manyak.admin.langfuse.public-key=",
        "manyak.admin.langfuse.secret-key=",
        "manyak.admin.langfuse.environment=",
        "manyak.analytics.amplitude.api-key=",
        "manyak.analytics.amplitude.secret-key=",
        "manyak.admin.play-stats.service-account-key-base64=",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
    ],
)
@MockitoBean(types = [OpenSearchClient::class, StorySearchIndexer::class])
abstract class AlternateConfigIntegrationTest : IntegrationTest()

/** 소셜 가입·탐색의 동의 게이트를 켠 조합(KNK-2053). 게이트가 켜지면 다른 테스트의 회원 API 응답이 바뀌어 따로 둔다. */
@TestPropertySource(properties = ["manyak.auth.consent-gate.enabled=true"])
abstract class ConsentGateIntegrationTest : IntegrationTest()

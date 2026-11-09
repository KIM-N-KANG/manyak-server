package com.knk.manyak.support

import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.push.outbox.PushOutboxRelay
import com.knk.manyak.push.outbox.PushOutboxStore
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.search.service.StorySearchIndexer
import com.knk.manyak.story.service.StoryExploreSeedSource
import com.knk.manyak.story.submission.StoryModerationClient
import org.opensearch.client.opensearch.OpenSearchClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * 외부 경계를 목으로 바꾼 조합(M, KNK-2053). 푸시 발송·이미지 저장소·검색 색인·탐색 시드처럼 바깥과 닿는 빈을
 * 목으로 공유한다. 이 클래스들은 원래 각자 필요한 목만 달아 클래스마다 컨텍스트가 하나씩 생겼다.
 *
 * - 목은 Spring 기본값(`MockReset.AFTER`)으로 테스트마다 reset된다. 목을 쓰는 테스트는 `@Autowired`로 받아 스텁한다.
 * - test 프로파일에서 실제 빈이 이미 꺼져 있거나 바깥으로 나가지 않는 것만 목으로 둔다. 미구성 S3 저장소는 null을
 *   돌려주고, 키 없는 FCM은 보내지 않으며, 검색 색인기는 클라이언트가 없으면 아무것도 하지 않는다. 그래서 같은
 *   조합의 다른 클래스에 주는 영향이 작다. 아웃박스 저장소·릴레이는 local 모드에서 빈 자체가 없고, 쓰는 쪽도 remote
 *   전용 빈뿐이라 목으로 추가돼도 local 경로가 바뀌지 않는다.
 * - 검수 클라이언트는 목이다. 이 조합에서 제출을 만드는 클래스는 검수 executor를 멈추거나 클라이언트를 직접 스텁한다.
 * - executor처럼 실제 동작이 필요한 클래스가 섞인 빈은 목이 아니라 루트의 스파이를 쓴다.
 * - `manyak.push.mode=remote` 조합은 [PushRemoteIntegrationTest]다(속성만 다르고 목은 같다).
 *
 * OpenSearchClient를 목으로 두면 검색 서비스가 OpenSearch 경로로 바뀐다. 그래서 이 조합에만 두고 기본 조합 D에는 두지 않는다.
 */
@MockitoBean(
    types = [
        FcmPushSender::class,
        UploadedImageStorage::class,
        StorySearchIndexer::class,
        OpenSearchClient::class,
        StoryExploreSeedSource::class,
        PushOutboxStore::class,
        PushOutboxRelay::class,
        StoryModerationClient::class,
    ],
)
abstract class MockedBoundaryIntegrationTest : IntegrationTest()

/** [MockedBoundaryIntegrationTest]에 `manyak.push.mode=remote`만 더한 조합(R). 트랜잭션 아웃박스 경로를 검증한다. */
@TestPropertySource(properties = [PushRemoteIntegrationTest.REMOTE])
abstract class PushRemoteIntegrationTest : MockedBoundaryIntegrationTest() {
    companion object {
        /** 공용 abstract 부모를 상속해 이 클래스를 상속할 수 없는 테스트는 같은 값으로 `@TestPropertySource`를 단다. */
        const val REMOTE = "manyak.push.mode=remote"
    }
}

# 리팩터링 내역

이 문서는 manyak-server의 리팩터링을 범주별로 나누고 대표 항목 다섯 개의 변경 전후를 적습니다. 기준은 2026-11-21까지 머지한 PR이고, 날짜는 KST입니다.

## 1. 범주

제목이 `Refactor`인 머지 PR은 64개입니다. 성능 개선은 `Perf` PR 12개로 따로 냈습니다.

| 범주 | PR 수 | 기간 | 예 |
| --- | --- | --- | --- |
| 이름과 계약 정렬 | 9 | 6/26~7/3 | [#80](https://github.com/KIM-N-KANG/manyak-server/pull/80) 분석 식별자 `anonymous_id` → `device_id`<br>[#93](https://github.com/KIM-N-KANG/manyak-server/pull/93) 플레이 세션 테이블 `chat` 명명 |
| 흐름과 설계 정리 | 6 | 7/6~9/1 | [#117](https://github.com/KIM-N-KANG/manyak-server/pull/117) 일반 제작 단발 등록 전환(-3,663줄)<br>[#166](https://github.com/KIM-N-KANG/manyak-server/pull/166) 계정 연동 프로토콜을 확정 설계로 정렬 |
| 안 쓰는 경로 제거 | 4 | 10/2~10/19 | [#333](https://github.com/KIM-N-KANG/manyak-server/pull/333) Google Play 결제 경로 제거<br>[#349](https://github.com/KIM-N-KANG/manyak-server/pull/349) 로그인 핸드오프 제거 |
| 모듈 경계 | 43 | 11/10~11/13 | [#484](https://github.com/KIM-N-KANG/manyak-server/pull/484) 의존 방향 뒤집기<br>[#497](https://github.com/KIM-N-KANG/manyak-server/pull/497) user 모듈을 auth로 통합<br>같은 기간의 정리 3건(#478, #479, #495) 포함 |
| 테스트 시간 | 2 | 11/10 | [#480](https://github.com/KIM-N-KANG/manyak-server/pull/480) 통합 테스트 컨텍스트 재사용<br>[#481](https://github.com/KIM-N-KANG/manyak-server/pull/481) 테스트 대기 단축과 컨테이너 공유 |

모듈 경계 범주의 기준선과 최종 결과는 README에 있습니다. ([2-6 리팩터링](../README.md#2-6-리팩터링))

## 2. 항목

효과 칸의 수치는 모두 PR 본문에 적은 측정값입니다.

### 2-1. 스토리 누적 턴 수 조회의 의존 방향 뒤집기

| 항목 | 내용 |
| --- | --- |
| PR | [#484](https://github.com/KIM-N-KANG/manyak-server/pull/484)(2026-11-10, 파일 5개, +39 -8) |
| 내역 | `StoryService`가 chat 모듈의 `StoryChatRepository`를 직접 불러 누적 턴 수를 셌음<br>story가 `StoryTurnCounts` 인터페이스를 정의하고 chat이 구현함<br>쿼리와 계산식은 chat에 그대로 둠 |
| 효과 | R1 268 → 266, R2 47 → 46, 양방향 쌍 16 → 15(`story -> chat` 간선 제거)<br>동작 변화 없음 |

**변경 전**

```kotlin
// story/service/StoryService.kt
import com.knk.manyak.chat.repository.StoryChatRepository

turnCount = storyChatRepository.sumTurnCountByStoryId(story.id),
```

**변경 후**

```kotlin
// story/service/StoryTurnCounts.kt
interface StoryTurnCounts {
    fun countByStoryId(storyId: Long): Long
    fun countByStoryIds(storyIds: Collection<Long>): Map<Long, Long>
}

// chat/service/ChatStoryTurnCounts.kt
@Component
class ChatStoryTurnCounts(private val storyChatRepository: StoryChatRepository) : StoryTurnCounts

// story/service/StoryService.kt
turnCount = storyTurnCounts.countByStoryId(story.id),
```

### 2-2. 채팅 턴의 이력 중복 로드 제거

| 항목 | 내용 |
| --- | --- |
| PR | [#495](https://github.com/KIM-N-KANG/manyak-server/pull/495)(2026-11-10, 파일 4개, +137 -18) |
| 내역 | 턴 한 번이 대화 이력 전체를 여러 번 읽었음<br>마지막 턴 ID만 필요한 두 곳은 한 행 조회로 바꿈<br>조립 트랜잭션 안에서는 이력을 한 번 읽어 넘김 |
| 효과 | 이력 전체 로드 횟수: 이어쓰기 3 → 1, 재생성 5 → 2, 입력 수정 6 → 3, 선택지 2 → 1<br>2,000행 채팅의 턴당 DB 송신량 약 12~25MB → 4~12MB |

PR은 지연 관점에서는 병목이 아니었다고 적었습니다. 2,000행에서도 이력 비용은 AI 스트리밍 시간(약 7.5초)의 0.35~0.73%였습니다. 줄인 것은 긴 채팅의 DB 송신량과 그만큼의 객체 할당입니다.

**변경 전**

```kotlin
// chat/service/ChatService.kt
private fun resolveRegenerateTarget(chatPk: Long, requestedTurnId: Long): RegenerateTarget {
    val all = storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chatPk)

private fun generationStartState(chat: StoryChat) = GenerationStartState(
    storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chat.id).lastOrNull { it.role == MessageRole.ASSISTANT }?.id,
```

**변경 후**

```kotlin
val messages = storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chat.id)
val target = resolveRegenerateTarget(messages, request.turnId)

private fun generationStartState(chat: StoryChat) = GenerationStartState(
    storyMessageRepository.findFirstByChatIdAndRoleAndHiddenAtIsNullOrderByMessageOrderDesc(chat.id, MessageRole.ASSISTANT)?.id,
```

### 2-3. 스토리 목록 집계 정렬에 커버링 인덱스 적용

| 항목 | 내용 |
| --- | --- |
| PR | [#507](https://github.com/KIM-N-KANG/manyak-server/pull/507)(Perf, 2026-11-11, 파일 6개, +90 -8) |
| 내역 | `story_chats(story_id)` 인덱스를 턴 수 컬럼을 포함한 커버링 인덱스로 바꿈(V126)<br>좋아요 수 집계를 `COUNT(l)`에서 `COUNT(*)`로 바꿔 인덱스만으로 답하게 함 |
| 효과 | 실제 PostgreSQL에서 데이터를 10배, 100배로 늘렸을 때 인기순 첫 페이지 4.0~4.8배, 채팅순 3.4~4.9배, 좋아요순 2.1~2.3배, 카드 누적 턴 집계 5~13배 빨라짐<br>정렬 결과(순서, 동률, 커서 값, 카드 수치)는 같음 |
| 비용 | 쓰기 한 건의 WAL 3.4~4.5% 증가<br>100배 데이터에서 인덱스 4.26MiB → 9.69MiB |

운영 규모에서는 차이가 1~3ms라 급하지 않았습니다. 비용이 작아 미리 넣었습니다.

**변경 전**

```sql
-- dbdoc/public.story_chats.md
CREATE INDEX idx_story_chats_story ON public.story_chats USING btree (story_id)
```

**변경 후**

```sql
-- V126__story_chats_story_turns_covering_index.sql
SET LOCAL lock_timeout = '5s';
CREATE INDEX idx_story_chats_story_turns ON story_chats (story_id)
    INCLUDE (current_turn, hidden_turn_count, regenerated_count, forked_from_turn);
DROP INDEX idx_story_chats_story;
```

### 2-4. 간편 제작 서비스 분할

| 항목 | 내용 |
| --- | --- |
| PR | [#536](https://github.com/KIM-N-KANG/manyak-server/pull/536)(2026-11-12, 파일 17개, +1,293 -1,225) |
| 내역 | 1,199줄인 `SimpleStoryCreationService`를 지우고 스토리라인 단계와 완성 단계 서비스로 나눔<br>공용 요청 기록과 복구 조회는 `StoryCreationRequestRecorder`로 옮김<br>트랜잭션 경계, 호출 순서, 잠금, 이벤트, 보상, API 응답은 그대로 둠 |
| 효과 | 서비스 1,199줄 → 614줄, 578줄로 분할<br>옮긴 멤버 37개 중 29개는 글자 그대로 옮김<br>테스트 3,872개 전부 통과 |

**변경 전**

```kotlin
class SimpleStoryCreationController(
    private val simpleStoryCreationService: SimpleStoryCreationService,
    private val storylineRatingService: StorylineRatingService,
)
    simpleStoryCreationService.generateSimpleStorylines(request, userId, deviceId)
    simpleStoryCreationService.createSimpleStory(request, userId, deviceId)
    simpleStoryCreationService.getCreationRequest(requestId, userId, deviceId)
```

**변경 후**

```kotlin
class SimpleStoryCreationController(
    private val simpleStorylineService: SimpleStorylineService,
    private val simpleStoryCompletionService: SimpleStoryCompletionService,
    private val storyCreationRequestRecorder: StoryCreationRequestRecorder,
    private val storylineRatingService: StorylineRatingService,
)
    simpleStorylineService.generateSimpleStorylines(request, userId, deviceId)
    simpleStoryCompletionService.createSimpleStory(request, userId, deviceId)
    storyCreationRequestRecorder.getCreationRequest(requestId, userId, deviceId)
```

### 2-5. 제출 접수의 업로드 이미지 확인을 동시에 보내기

| 항목 | 내용 |
| --- | --- |
| PR | [#544](https://github.com/KIM-N-KANG/manyak-server/pull/544)(Perf, 2026-11-17, 파일 7개, +449 -28) |
| 내역 | 제출 등록, 수정, 재제출이 업로드 이미지마다 S3 HEAD를 하나씩 보냈고, 그동안 회원 행 잠금을 쥐고 있었음<br>`UploadedObjectHeads`가 HEAD를 가상 스레드로 미리 동시에 보냄(호출당 최대 8개)<br>없는 키나 실패를 만나면 바로 멈춤 |
| 효과 | 지연 30ms인 HEAD 61개에서 접수 시간 2,205~2,563ms → 304~541ms<br>HEAD가 장당 5초 가까이 걸리는 최악의 경우 잠금 보유 약 305초 → 약 40초 |

기존 순차 검증은 그대로 두고 미리 받은 결과로 판정합니다. 그래서 오류 순서와 응답은 바뀌지 않습니다.

**변경 전**

```kotlin
// story/service/StoryImageAccess.kt
requirePrefix(expectedPrefixes, objectKey, mismatchMessage)
val uploaded = uploadedImageStorage.head(objectKey)
```

**변경 후**

```kotlin
// story/service/StoryImageAccess.kt
fun prefetchUploaded(story: Story?, userId: Long, objects: List<Pair<UploadedImageKind, String>>): Map<String, Result<UploadedObject?>> {
    if (objects.size < 2 || !uploadedImageStorage.isEnabled()) return emptyMap()
    // 접두사를 검사한 키만 모음(생략)
    return heads.headUntilMissing(keys)
}

val uploaded = prefetched[objectKey]?.getOrThrow()
    ?: if (objectKey in prefetched) null else uploadedImageStorage.head(objectKey)
```

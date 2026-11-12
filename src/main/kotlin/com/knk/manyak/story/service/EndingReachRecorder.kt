package com.knk.manyak.story.service

import com.knk.manyak.story.dto.EndingReachBackfill
import com.knk.manyak.story.entity.UserStoryEndingReach
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.UserStoryEndingReachRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 회원 엔딩 도달 집계를 **독립 트랜잭션(REQUIRES_NEW)** 으로 기록한다.
 *
 * `existsBy`→`save`는 원자적이지 않아, 같은 회원이 두 채팅에서 동시에 같은 엔딩에 도달하면 둘 다 exists 검사를
 * 통과한 뒤 한쪽 insert가 `uq_user_story_ending_reaches_name`을 위반한다. 이 기록을 턴 저장과 같은 트랜잭션에서 하면
 * 그 위반이 턴 전체(메시지·선택지·상태)를 롤백시킨다 — AI 응답을 이미 생성한 뒤라 손해가 크다.
 *
 * 그래서 도달 기록만 새 트랜잭션으로 떼어, 유니크 위반이 나면 이 트랜잭션만 롤백되고 호출부(턴 저장)는 유지되게 한다.
 * 위반 예외는 호출부가 잡아 멱등 결과로 흡수한다(다른 트랜잭션이 이미 같은 도달을 기록함).
 *
 * 호출부는 chat의 턴 저장(`ChatTurnPersister`)이지만 `user_story_ending_reaches`가 story 소유 테이블이라 story 모듈에 둔다
 * (KNK-2055). 별도 빈이어야 REQUIRES_NEW가 프록시를 타므로 호출부 클래스에 합치지 않는다.
 * 게스트 이관의 백필([backfillIfAbsent])만 예외로 호출자 트랜잭션에 합류한다(KNK-2080).
 */
@Component
class EndingReachRecorder(
    private val userStoryEndingReachRepository: UserStoryEndingReachRepository,
    private val storyEndingRepository: StoryEndingRepository,
) {

    /**
     * [endingId]는 도달 시점에 라이브 엔딩 행을 찾았을 때만 있다. 없어도 [endingName]만으로 기록한다 —
     * 집계의 정본 식별자가 이름이기 때문이다(V70). 예전에는 id가 없으면 집계를 통째로 건너뛰어,
     * 제작자가 그 엔딩을 다시 공개해도 영원히 복구되지 않았다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(userId: Long, storyId: Long, endingId: Long?, endingName: String) {
        if (userStoryEndingReachRepository.existsByUserIdAndStoryIdAndEndingNameSnapshot(userId, storyId, endingName)) {
            return
        }
        // saveAndFlush로 위반을 이 메서드 경계 안에서 즉시 드러낸다. 예외는 이 REQUIRES_NEW 트랜잭션만 롤백시키고
        // 호출부로 전파되며, 호출부가 잡아 흡수한다(턴 트랜잭션은 무영향).
        userStoryEndingReachRepository.saveAndFlush(
            UserStoryEndingReach(
                userId = userId,
                storyId = storyId,
                endingNameSnapshot = endingName,
                endingId = endingId,
            ),
        )
    }

    /**
     * 게스트 이관이 클레임한 채팅들의 도달을 회원 도달 집계에 최초 1회 백필한다(STORY 4-3-10 이관 백필, KNK-2080).
     * [record]와 달리 **호출자(이관) 트랜잭션에 합류**한다. 이관의 회원 잠금 트랜잭션에서 클레임과 함께 커밋되고, 저장은
     * flush하지 않아 커밋 때 반영된다.
     *
     * 이름이 정본이다(V70). 이름 스냅샷이 없으면(V67 이전 채팅과 그 배포 창) 라이브 엔딩에서 해소하고, 둘 다 없으면 건너뛴다.
     * 이미 있는 도달은 한 번의 조회로 거르고, 같은 요청 안의 같은 (스토리, 엔딩 이름)은 첫 채팅의 것만 남긴다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun backfillIfAbsent(userId: Long, reaches: List<EndingReachBackfill>) {
        val named = reaches.mapNotNull { reach ->
            val name = reach.endingNameSnapshot
                ?: reach.endingId?.let { storyEndingRepository.findById(it).orElse(null)?.name }
                ?: return@mapNotNull null
            UserStoryEndingReach(userId = userId, storyId = reach.storyId, endingNameSnapshot = name, endingId = reach.endingId)
        }.distinctBy { it.storyId to it.endingNameSnapshot }
        if (named.isEmpty()) return
        // 집계 행의 (회원, 스토리, 이름)은 유니크 키다(V71). 이름 하나로 확인하면 충분하다.
        val existing = userStoryEndingReachRepository.findByUserIdAndStoryIdIn(userId, named.map { it.storyId }.toSet())
            .map { it.storyId to it.endingNameSnapshot }.toSet()
        userStoryEndingReachRepository.saveAll(named.filterNot { (it.storyId to it.endingNameSnapshot) in existing })
    }
}

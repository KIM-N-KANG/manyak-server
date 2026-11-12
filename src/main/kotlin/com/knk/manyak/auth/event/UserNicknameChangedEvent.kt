package com.knk.manyak.auth.event

/**
 * 회원의 표시 닉네임이 바뀌었다는 사실(KNK-2062). 대소문자만 바뀐 경우도 포함한다.
 *
 * [com.knk.manyak.auth.service.ProfileUpdater]가 사용자 행을 잠그고 닉네임을 저장한 **그 트랜잭션 안에서** 발행한다.
 * story가 동기 `@EventListener` + `@Transactional(propagation = MANDATORY)`로 같은 트랜잭션에 참여해 소유 스토리마다
 * 색인 요청을 발행한다([com.knk.manyak.story.event.StoryNicknameReindexListener]). 색인 자체는 커밋 후 비동기로 돈다.
 * 재색인 대상 조회가 실패하면 예외가 전파돼 프로필 수정 전체가 롤백된다.
 */
data class UserNicknameChangedEvent(val userId: Long)

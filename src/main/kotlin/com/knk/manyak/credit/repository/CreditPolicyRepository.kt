package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.CreditPolicy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 정책은 키 개수만큼(현재 6개)만 존재하는 초소형 테이블이라 항상 전건을 읽어 캐시한다. */
interface CreditPolicyRepository : JpaRepository<CreditPolicy, String> {

    /**
     * 정책 스냅샷 적재 전용 조회. **독립 트랜잭션**([Propagation.REQUIRES_NEW])이다.
     *
     * 이유(Codex 리뷰 P2): 정책 읽기는 `@Transactional` 안에서 일어난다
     * ([com.knk.manyak.credit.service.AttendanceRewardService.claimDailyAttendance]·
     * [com.knk.manyak.invite.service.InviteService.redeem] 등). 기본 전파(REQUIRED)면 이 조회가 참여 중인
     * 바깥 트랜잭션에 붙어서, 실패가 그 트랜잭션을 **rollback-only로 찍는다**. 그러면
     * [com.knk.manyak.credit.service.CreditPolicyService]가 예외를 잡아 직전 스냅샷을 돌려줘도 **커밋이 실패**해
     * "정책 조회가 실패해도 요청을 막지 않는다"는 설계 목표가 성립하지 않는다.
     * 독립 트랜잭션이면 실패가 이 안에 갇혀 바깥은 멀쩡히 커밋한다.
     *
     * 리포지토리 프록시 자체가 트랜잭션 프록시라 인터페이스 메서드에 붙인 이 애노테이션이 그대로 경계가 된다
     * (서비스 안에서 자기 호출로는 REQUIRES_NEW가 걸리지 않는 함정을 애초에 피한다).
     *
     * 커넥션: REQUIRES_NEW는 바깥 트랜잭션의 커넥션을 쥔 채 두 번째를 잡는다. Hikari 풀은 별도 설정이 없어
     * Spring Boot 기본값 10이고, 이 조회는 캐시 갱신 때만 — 인스턴스당 TTL(기본 60초)에 1회, 그것도 락으로
     * 직렬화돼 동시 1건 — 발생하므로 풀을 압박하지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    override fun findAll(): List<CreditPolicy>
}

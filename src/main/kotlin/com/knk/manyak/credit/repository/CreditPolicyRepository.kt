package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.CreditPolicy
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 정책은 키 개수만큼(현재 6개)만 존재하는 초소형 테이블이라 항상 전건을 읽어 메모리에 둔다.
 *
 * 조회는 [com.knk.manyak.credit.service.CreditPolicyService.refresh]에서만 일어나고, 그 호출자는 부팅 선적재와
 * 스케줄러뿐이라 **호출자 트랜잭션이 없다**. 그래서 전파 설정이 따로 필요 없다(크레딧 경로의 `@Transactional`
 * 안에서 조회하지 않으므로 rollback-only 오염도, 두 번째 커넥션 점유도 생기지 않는다).
 */
interface CreditPolicyRepository : JpaRepository<CreditPolicy, String>

package com.knk.manyak.credit.repository

import com.knk.manyak.credit.entity.CreditPolicy
import org.springframework.data.jpa.repository.JpaRepository

/** 정책은 키 개수만큼(현재 6개)만 존재하는 초소형 테이블이라 항상 전건을 읽어 캐시한다. */
interface CreditPolicyRepository : JpaRepository<CreditPolicy, String>

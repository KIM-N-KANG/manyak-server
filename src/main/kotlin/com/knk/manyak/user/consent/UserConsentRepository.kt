package com.knk.manyak.user.consent

import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

interface UserConsentRepository : Repository<UserConsent, UserConsentId> {
    fun findAllByUserId(userId: Long): List<UserConsent>

    // H2 PostgreSQL 모드는 충돌 대상 컬럼 구문을 지원하지 않는다. 유일한 유니크 제약이 복합 PK라
    // 대상 생략도 같은 충돌만 무시한다. 예외로 처리하지 않아 트랜잭션과 최초 agreed_at을 보존한다.
    @Modifying
    @Query(
        value = """
            INSERT INTO user_consents (user_id, doc_type, version, agreed_at)
            VALUES (:userId, :docType, :version, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(userId: Long, docType: String, version: String): Int
}

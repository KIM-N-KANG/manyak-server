package com.knk.manyak.global.observability.aicall

import org.springframework.data.jpa.repository.JpaRepository

interface AiCallLogRepository : JpaRepository<AiCallLog, Long>

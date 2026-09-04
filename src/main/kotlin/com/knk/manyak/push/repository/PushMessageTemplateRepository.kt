package com.knk.manyak.push.repository

import com.knk.manyak.push.entity.PushMessageTemplate
import org.springframework.data.jpa.repository.JpaRepository

interface PushMessageTemplateRepository : JpaRepository<PushMessageTemplate, Long>

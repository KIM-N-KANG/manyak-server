package com.knk.manyak.feedback.repository

import com.knk.manyak.feedback.entity.Feedback
import org.springframework.data.jpa.repository.JpaRepository

interface FeedbackRepository : JpaRepository<Feedback, Long>

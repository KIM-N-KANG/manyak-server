package com.knk.manyak.push.inbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InboxDeepLinkTests {
    @Test fun `여섯 종류의 분류는 정본 대응을 따른다`() {
        assertThat(NotificationType.entries.associateWith { it.category }).containsExactlyInAnyOrderEntriesOf(mapOf(
            NotificationType.STORY_COMPLETED to NotificationCategory.ACTIVITY,
            NotificationType.STORY_MODERATION_COMPLETED to NotificationCategory.ACTIVITY,
            NotificationType.ATTENDANCE_REMINDER to NotificationCategory.ACTIVITY,
            NotificationType.CREDIT_EXPIRY_REMINDER to NotificationCategory.ACTIVITY,
            NotificationType.NOTICE to NotificationCategory.NEWS,
            NotificationType.PROMOTION to NotificationCategory.NEWS,
        ))
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(NotificationType::class)
    fun `내역과 푸시는 source와 medium 외에 같은 링크를 만든다`(type: NotificationType) {
        val campaign = "ce240891-8eda-4247-a165-6e10172297bf"
        val push = com.knk.manyak.push.service.pushDeepLink("https://manyak.app/", "/studio?tab=all#section", type.name, campaign)
        assertThat(inboxDeepLink("https://manyak.app/", "/studio?tab=all#section", type, campaign))
            .isEqualTo(push.replace("utm_source=push&utm_medium=push", "utm_source=inbox&utm_medium=inbox"))
    }

    @Test fun `기존 쿼리를 유지하고 캠페인 값을 인코딩하며 경로가 없으면 링크가 없다`() {
        assertThat(inboxDeepLink("https://manyak.app/", "/my/credits?tab=free", NotificationType.ATTENDANCE_REMINDER))
            .isEqualTo("https://manyak.app/my/credits?tab=free&utm_source=inbox&utm_medium=inbox&utm_campaign=attendance_reminder&utm_content=attendance_reminder")
        assertThat(inboxDeepLink("https://manyak.app", "/notices/id", NotificationType.PROMOTION, "a b/&"))
            .isEqualTo("https://manyak.app/notices/id?utm_source=inbox&utm_medium=inbox&utm_campaign=a%20b%2F%26&utm_content=promotion")
        assertThat(inboxDeepLink("https://manyak.app", null, NotificationType.PROMOTION, "campaign")).isNull()
    }
}

package com.knk.manyak.push.inbox

import com.knk.manyak.push.service.pushDeepLink

/** 내역의 nullable 경로를 받되 URL 조립은 푸시와 같은 공용 함수에 위임한다. */
internal fun inboxDeepLink(base: String, path: String?, type: NotificationType, campaignId: String? = null): String? =
    path?.let { pushDeepLink(base, it, type.name, campaignId, source = "inbox", medium = "inbox") }

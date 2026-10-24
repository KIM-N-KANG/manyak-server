package com.knk.manyak.push.service

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale

/** 푸시와 알림 내역이 같은 경로·캠페인 규칙으로 UTM을 가진 절대 링크를 만든다. */
fun pushDeepLink(
    baseUrl: String, path: String, type: String, campaignId: String? = null,
    source: String = "push", medium: String = "push",
): String {
    val code = type.lowercase(Locale.ROOT)
    val campaign = when (type) {
        "NOTICE", "PROMOTION" -> requireNotNull(campaignId) { "관리자 캠페인 공개 식별자가 필요합니다." }
        else -> code
    }
    fun encode(value: String) = URLEncoder.encode(value, UTF_8).replace("+", "%20")
    val url = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
    val destination = url.substringBefore('#')
    val fragment = url.substringAfter('#', "").let { if ('#' in url) "#$it" else "" }
    val separator = when {
        destination.endsWith('?') || destination.endsWith('&') -> ""
        '?' in destination -> "&"
        else -> "?"
    }
    return destination + separator + "utm_source=${encode(source)}&utm_medium=${encode(medium)}&utm_campaign=${encode(campaign)}&utm_content=${encode(code)}" + fragment
}

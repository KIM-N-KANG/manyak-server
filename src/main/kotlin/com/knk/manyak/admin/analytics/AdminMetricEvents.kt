package com.knk.manyak.admin.analytics

object AdminMetricEvents {
    const val GENERATE = "client_storyCreate_storyGeneration_requested"
    const val COMPLETE = "client_storyCreate_storyCompletion_requested"
    const val MESSAGE = "client_chat_messageInput_submitted"
    const val GOOGLE = "server_login_googleLogin_processed_succeeded"
    const val KAKAO = "server_login_kakaoLogin_processed_succeeded"
    const val CHAT_SUCCEEDED = "server_chat_aiMessage_processed_succeeded"
    const val CHAT_START = "client_storyDetail_chatStartButton_clicked"
    val creation = listOf("client_onboarding_viewed", "client_onboarding_createButton_clicked", GENERATE,
        "client_storyCreate_storylineOption_selected", COMPLETE, MESSAGE)
    fun filter(key: String, values: List<String>, type: String = "event", op: String = "is") = mapOf("subprop_type" to type, "subprop_key" to key, "subprop_op" to op, "subprop_value" to values)
    fun event(name: String, filters: List<Map<String, Any>> = emptyList()): Map<String, Any> =
        mapOf("event_type" to name) + if (filters.isEmpty()) emptyMap() else mapOf("filters" to filters)
    fun condition(key: String, values: List<String>, op: String = "is") = mapOf("prop" to key, "op" to op, "values" to values)
    /** 조건 순서도 저장 키의 일부다. 기존 국내 조건 → 플랫폼 순서를 유지한다. */
    fun platformFilters(platform: String): List<Map<String, Any>> = when (platform) {
        "android_overseas" -> listOf(filter("country", listOf("South Korea", "(none)"), "user", "is not"),
            filter("platform", listOf("Android"), "user"), filter("user_id", listOf("(none)"), "user", "is not"))
        "all" -> common(false) + filter("platform", listOf("Android"), "user", "is not")
        "web" -> common()
        "android" -> common(false) + filter("platform", listOf("Android"), "user")
        "unknown" -> common(false) + filter("platform", listOf("(none)"), "user")
        else -> error("Invalid platform")
    }
    /** 게스트는 이벤트에 회원 식별자(user_id)가 없는 것, 회원은 있는 것이다. 앱·해외 로그인 조건과 같은 표현이다. */
    fun segment(segment: String): List<Map<String, Any>> = when (segment) {
        "all" -> emptyList()
        "guest" -> listOf(filter("user_id", listOf("(none)"), "user"))
        "member" -> listOf(filter("user_id", listOf("(none)"), "user", "is not"))
        else -> error("Invalid segment")
    }
    fun common(web: Boolean = true): List<Map<String, Any>> = listOf(filter("country", listOf("South Korea", "(none)"), "user")) +
        if (web) listOf(filter("platform", listOf("Web"), "user")) else emptyList()
}

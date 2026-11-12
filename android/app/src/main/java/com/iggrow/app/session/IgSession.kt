package com.iggrow.app.session

data class IgSession(
    val dsUserId: String,
    val username: String,
    val sessionId: String,
    val csrfToken: String,
    val cookieHeader: String,
    val fbDtsg: String,
    val lsd: String,
    val userAgent: String,
    val jazoest: String = "",
    val wwwClaim: String = "",
    val igAppId: String = "936619743392459",
    val asbdId: String = "359341",
    val capturedAtMs: Long = System.currentTimeMillis()
) {
    fun isComplete(): Boolean =
        dsUserId.isNotBlank() && sessionId.isNotBlank() && csrfToken.isNotBlank()
}

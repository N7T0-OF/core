package com.maxrave.domain.data.model.update

data class UpdateData(
    val tagName: String,
    val releaseTime: String?,
    val body: String,
    // SPACEKAI FEATURE: direct URL of the APK asset for this release, so the
    // update dialog can download + install it in-app instead of opening the
    // releases page in a browser.
    val apkUrl: String? = null,
)
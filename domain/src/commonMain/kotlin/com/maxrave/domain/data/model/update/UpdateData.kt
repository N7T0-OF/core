package com.maxrave.domain.data.model.update

data class UpdateData(
    val tagName: String,
    val releaseTime: String?,
    val body: String,
    // SPACEKAI FEATURE: direct URL of the APK asset for this release, so the
    // update dialog can download + install it in-app instead of opening the
    // releases page in a browser.
    val apkUrl: String? = null,
    // SPACEKAI FEATURE: direct URL of the SHA256SUMS.txt asset for this release,
    // so the app can verify the downloaded APK's integrity before installing and
    // refuse a corrupted/tampered file. Null when the release carries no checksums.
    val checksumsUrl: String? = null,
    /** True when this candidate came from a GitHub prerelease. */
    val isPrerelease: Boolean = false,
)

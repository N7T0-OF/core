package com.maxrave.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateRepositoryAssetResolverTest {
    private fun asset(name: String): GithubReleaseAsset =
        GithubReleaseAsset(
            name = name,
            url = "https://github.com/N7T0-OF/Sankamusic/releases/download/v0.3.7/$name",
        )

    @Test
    fun selectsTheCanonicalApkRegardlessOfGitHubAssetOrder() {
        val universal = asset("SpaceKai-v0.3.7.apk")
        val arm64 = asset("sankamusic-v0.3.7-arm64.apk")

        assertEquals(
            universal.url,
            resolveApkUrl(listOf(arm64, universal), "v0.3.7"),
        )
        assertEquals(
            universal.url,
            resolveApkUrl(listOf(universal, arm64), "v0.3.7"),
        )
    }

    @Test
    fun acceptsTheExplicitUniversalReleaseFixtureName() {
        val universal = asset("sankamusic-v0.3.7-universal-release.apk")

        assertEquals(
            universal.url,
            resolveApkUrl(listOf(asset("sankamusic-v0.3.7-arm64.apk"), universal), "v0.3.7"),
        )
    }

    @Test
    fun ignoresPlausibleButNonCanonicalApkBeforeTheCanonicalAsset() {
        val canonical = asset("SpaceKai-v0.3.7.apk")
        val unrelated = asset("some-other-release.apk")

        assertEquals(
            canonical.url,
            resolveApkUrl(listOf(unrelated, canonical), "v0.3.7"),
        )
    }

    @Test
    fun ignoresAnAssetForADifferentReleaseVersion() {
        val canonical = asset("SpaceKai-v0.3.7.apk")
        val stale = asset("SpaceKai-v0.3.6.apk")

        assertEquals(
            canonical.url,
            resolveApkUrl(listOf(stale, canonical), "v0.3.7"),
        )
    }

    @Test
    fun rejectsAnAmbiguousSetOfCanonicalApks() {
        assertNull(
            resolveApkUrl(
                listOf(
                    asset("SpaceKai-v0.3.7.apk"),
                    asset("sankamusic-v0.3.7-universal-release.apk"),
                ),
                "v0.3.7",
            ),
        )
    }

    @Test
    fun rejectsOnlyDebugOrAbiSplitAssets() {
        assertNull(
            resolveApkUrl(
                listOf(
                    asset("SpaceKai-v0.3.7-debug.apk"),
                    asset("sankamusic-v0.3.7-arm64.apk"),
                ),
                "v0.3.7",
            ),
        )
    }

    @Test
    fun prereleaseTagMayUseTheBaseVersionPublicArtifactName() {
        val beta = asset("SpaceKai-v0.3.8.apk")

        assertEquals(beta.url, resolveApkUrl(listOf(beta), "v0.3.8-beta.1"))
    }
}

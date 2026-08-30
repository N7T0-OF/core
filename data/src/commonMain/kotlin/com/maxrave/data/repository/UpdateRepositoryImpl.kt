package com.maxrave.data.repository

import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.repository.UpdateRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal class UpdateRepositoryImpl(
    private val youTube: YouTube,
) : UpdateRepository {
    // SPACEKAI FEATURE: the actual GitHub release URL is pointed at SpaceKai
    // (N7T0-OF/Sankamusic) in Ytmusic.kt (kotlinYtmusicScraper module), which is
    // where the HTTP call lives. SpaceKaiUpdateConfig (composeApp/spacekai/) is
    // the source of truth for the repo owner/name — this module cannot import it
    // directly (Clean Architecture: core must not depend on the app layer),
    // so the URL is hard-coded in Ytmusic.kt instead. After every upstream
    // merge, verify that Ytmusic.kt still points at N7T0-OF/Sankamusic.
    override fun checkForGithubReleaseUpdate(): Flow<Resource<UpdateData>> =
        flow {
            youTube
                .checkForGithubReleaseUpdate()
                .onSuccess { response ->
                    emit(
                        Resource.Success(
                            UpdateData(
                                tagName = response.tagName ?: "",
                                releaseTime = response.publishedAt ?: "",
                                body = response.body ?: "",
                                // SPACEKAI FEATURE: resolve the universal APK asset so the
                                // dialog can install it in-app without opening the browser.
                                // Accept .apk files, preferring the universal build (no ABI
                                // suffix) so it installs on any device.
                                apkUrl = resolveApkUrl(response.assets?.mapNotNull { it?.browserDownloadUrl }),
                            ),
                        ),
                    )
                }.onFailure {
                    emit(Resource.Error<UpdateData>(it.localizedMessage ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun checkForFdroidUpdate(): Flow<Resource<UpdateData>> =
        flow {
            youTube
                .checkForFdroidUpdate()
                .onSuccess { response ->
                    val latestVersion = response.packages.maxBy { it.versionCode }
                    emit(
                        Resource.Success(
                            UpdateData(
                                tagName = latestVersion.versionName,
                                releaseTime = null,
                                body =
                                    $$"""
                                    ### Update via F-Droid, changelogs: 
                                    - https://github.com/maxrave-dev/SimpMusic/blob/dev/fastlane/metadata/android/en-US/changelogs/$${latestVersion.versionCode}.txt
                                    """.trimIndent(),
                            ),
                        ),
                    )
                }.onFailure {
                    emit(Resource.Error<UpdateData>(it.localizedMessage ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    // SPACEKAI FEATURE: prefer a universal APK asset (name without an ABI suffix) so the
    // downloaded build installs on the target device; fall back to any .apk.
    private fun resolveApkUrl(assets: List<String>?): String? {
        if (assets.isNullOrEmpty()) return null
        val abiSuffixes =
            listOf(
                "-arm64-v8a", "-armeabi-v7a", "-x86_64", "-universal", "-armv7a", "-arm64",
            )
        return assets
            .firstOrNull { it.endsWith(".apk") && abiSuffixes.none { s -> s in it } }
            ?: assets.firstOrNull { it.endsWith(".apk") }
    }
}
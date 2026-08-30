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
                                // dialog can install it in-app without opening the browser,
                                // and the SHA256SUMS.txt asset so integrity is verified first.
                                apkUrl = resolveApkUrl(response.assets?.mapNotNull { it?.browserDownloadUrl }),
                                checksumsUrl =
                                    response.assets
                                        ?.mapNotNull { it?.browserDownloadUrl }
                                        ?.firstOrNull { url -> url.substringAfterLast('/').equals("SHA256SUMS.txt", ignoreCase = true) },
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

    // SPACEKAI FEATURE: latest SimpMusic (upstream) release, INFO-ONLY. The UPSTREAM
    // APK is deliberately NOT resolved/installed — it is signed with a different key than
    // SpaceKai, so installing it over SpaceKai would either be refused or replace SpaceKai.
    // SharedViewModel uses this only to compute the compatibility matrix.
    override fun checkForUpstreamRelease(): Flow<Resource<UpdateData>> =
        flow {
            youTube
                .checkForUpstreamRelease()
                .onSuccess { response ->
                    emit(
                        Resource.Success(
                            UpdateData(
                                tagName = response.tagName ?: "",
                                releaseTime = response.publishedAt ?: "",
                                body = response.body ?: "",
                            ),
                        ),
                    )
                }.onFailure {
                    emit(Resource.Error<UpdateData>(it.localizedMessage ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    // SPACEKAI FEATURE: prefer a universal, release, correctly-signed APK asset so the
    // downloaded build installs on the target device; fall back to any .apk. Never pick
    // debug/unsigned builds or ABI-split slices — the spacekai release only carries the
    // universal one, but a stray -debug.apk would otherwise win the preference and
    // "install" would fail or be unsigned.
    private fun resolveApkUrl(assets: List<String>?): String? {
        if (assets.isNullOrEmpty()) return null
        val excludedTokens =
            listOf(
                "-debug", "-unsigned", "-signed-", "-test", "\\.breakpoints",
                "-arm64-v8a", "-armeabi-v7a", "-x86_64", "-universal", "-armv7a", "-arm64",
            )
        return assets
            .firstOrNull {
                it.endsWith(".apk") && excludedTokens.none { token -> token in it }
            }
            ?: assets.firstOrNull { it.endsWith(".apk") }
    }
}
package com.maxrave.data.repository

import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.repository.UpdateRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.simpmusic.GithubResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal data class GithubReleaseAsset(
    val name: String,
    val url: String,
)

internal class UpdateRepositoryImpl(
    private val youTube: YouTube,
) : UpdateRepository {
    override fun checkForGithubReleaseUpdate(includePrereleases: Boolean): Flow<Resource<UpdateData>> =
        flow {
            if (includePrereleases) {
                youTube
                    .checkForGithubReleaseUpdates()
                    .fold(
                        onSuccess = { releases ->
                            // GitHub normally returns newest-first, but keep the
                            // selection deterministic instead of relying on that API
                            // ordering contract.
                            val release =
                                releases
                                    .asSequence()
                                    .filter { response ->
                                        response.draft != true &&
                                            response.prerelease == true &&
                                            !response.tagName.isNullOrBlank()
                                    }.maxWithOrNull(
                                        compareBy<GithubResponse> {
                                            it.publishedAt ?: it.createdAt ?: ""
                                        }.thenBy { it.tagName ?: "" },
                                    )
                            if (release == null) {
                                emit(Resource.Error<UpdateData>("No published GitHub release found"))
                            } else {
                                emit(Resource.Success(release.toUpdateData()))
                            }
                        },
                        onFailure = {
                            emit(Resource.Error<UpdateData>(it.localizedMessage ?: "Unknown error"))
                        },
                    )
            } else {
                youTube
                    .checkForGithubReleaseUpdate()
                    .fold(
                        onSuccess = { response ->
                            emit(Resource.Success(response.toUpdateData()))
                        },
                        onFailure = {
                            emit(Resource.Error<UpdateData>(it.localizedMessage ?: "Unknown error"))
                        },
                    )
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

    private fun GithubResponse.toUpdateData(): UpdateData {
        val releaseAssets =
            assets?.mapNotNull { asset ->
                val source = asset ?: return@mapNotNull null
                val url = source.browserDownloadUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GithubReleaseAsset(
                    name =
                        source.name
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: url.substringAfterLast('/').substringBefore('?'),
                    url = url,
                ).takeIf { it.name.isNotBlank() }
            }
        return UpdateData(
            tagName = tagName ?: "",
            releaseTime = publishedAt ?: createdAt,
            body = body ?: "",
            // SPACEKAI FEATURE: resolve the universal APK asset so the dialog can
            // install it in-app and verify the release checksum first.
            apkUrl = resolveApkUrl(releaseAssets, tagName),
            checksumsUrl =
                releaseAssets
                    ?.firstOrNull { asset ->
                        asset.name.equals("SHA256SUMS.txt", ignoreCase = true)
                    }?.url,
            isPrerelease = prerelease == true,
        )
    }

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
                                isPrerelease = response.prerelease == true,
                            ),
                        ),
                    )
                }.onFailure {
                    emit(Resource.Error<UpdateData>(it.localizedMessage ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)
}

// SPACEKAI FEATURE: resolve exactly one canonical universal/release APK. GitHub's
// asset order is not a contract: never select the first plausible .apk, because a
// debug, ABI-split or stale artifact could appear before the real release asset.
// Only explicit names derived from the release tag are eligible, and multiple eligible
// assets are treated as ambiguous. Returning null is deliberate fail-closed behavior;
// the updater then refuses to download anything instead of guessing.
internal fun resolveApkUrl(
    assets: List<GithubReleaseAsset>?,
    releaseTag: String?,
): String? {
    val expectedNames = expectedReleaseApkNames(releaseTag)
    if (expectedNames.isEmpty()) return null

    val candidates =
        assets.orEmpty().filter { asset ->
            val name = asset.name.trim().lowercase()
            name in expectedNames &&
                name.endsWith(".apk") &&
                FORBIDDEN_APK_TOKENS.none { token -> token in name }
        }
    return candidates.singleOrNull()?.url
}

private fun expectedReleaseApkNames(releaseTag: String?): Set<String> {
    val normalizedTag =
        releaseTag
            ?.trim()
            ?.let { tag -> if (tag.startsWith("v", ignoreCase = true)) tag.drop(1) else tag }
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return emptySet()
    val baseVersion =
        normalizedTag
            .substringBefore('-')
            .substringBefore('_')
            .substringBefore('+')
    if (!SEMVER_TRIPLE.matches(baseVersion)) return emptySet()

    // SpaceKai-v<version>.apk is the current public pipeline name. The explicit
    // universal-release spellings are retained for temporary/legacy fixtures; they
    // are still exact names, never a generic "any APK" fallback.
    return setOf(
        "spacekai-v$normalizedTag.apk",
        "spacekai-v$baseVersion.apk",
        "spacekai-v$normalizedTag-universal-release.apk",
        "spacekai-v$baseVersion-universal-release.apk",
        "sankamusic-v$normalizedTag-universal-release.apk",
        "sankamusic-v$baseVersion-universal-release.apk",
    )
}

private val SEMVER_TRIPLE = Regex("""\d+\.\d+\.\d+""")
private val FORBIDDEN_APK_TOKENS =
    listOf(
        "-debug", "-unsigned", "-signed", "-test", ".breakpoints",
        "-arm64-v8a", "-armeabi-v7a", "-x86_64", "-x86", "-armeabi",
        "-armv7a", "-arm64", "-foss", "-aligned",
    )

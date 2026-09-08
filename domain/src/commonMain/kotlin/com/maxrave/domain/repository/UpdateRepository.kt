package com.maxrave.domain.repository

import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface UpdateRepository {
    /**
     * Checks the current app release channel. The default keeps the historical
     * stable-only behavior; beta-aware callers request the published release list.
     */
    fun checkForGithubReleaseUpdate(includePrereleases: Boolean = false): Flow<Resource<UpdateData>>
    fun checkForFdroidUpdate(): Flow<Resource<UpdateData>>
    /**
     * SPACEKAI FEATURE: latest SimpMusic (upstream) release, INFO-ONLY.
     * Never installs the upstream APK over SpaceKai — used by the compatibility
     * matrix to tell whether the installed layer still runs on the newest base.
     */
    fun checkForUpstreamRelease(): Flow<Resource<UpdateData>>
}
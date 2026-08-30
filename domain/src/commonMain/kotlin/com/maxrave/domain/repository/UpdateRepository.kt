package com.maxrave.domain.repository

import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface UpdateRepository {
    fun checkForGithubReleaseUpdate(): Flow<Resource<UpdateData>>
    fun checkForFdroidUpdate(): Flow<Resource<UpdateData>>
    /**
     * SPACEKAI FEATURE: latest SimpMusic (upstream) release, INFO-ONLY.
     * Never installs the upstream APK over SpaceKai — used by the compatibility
     * matrix to tell whether the installed layer still runs on the newest base.
     */
    fun checkForUpstreamRelease(): Flow<Resource<UpdateData>>
}
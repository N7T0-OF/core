package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SpotifyPlaylistItem
import com.maxrave.domain.repository.SpotifySyncProgress
import com.maxrave.domain.repository.SpotifySyncRepository
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import com.maxrave.spotify.SpotifyPkce
import com.maxrave.spotify.SpotifyClient
import com.maxrave.spotify.model.response.spotify.playlist.SpotifyPlaylist
import com.maxrave.spotify.model.response.spotify.playlist.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private const val TAG = "SpotifySyncRepo"

internal class SpotifySyncRepositoryImpl(
    private val spotify: Spotify,
    private val youtube: YouTube,
    private val localDataSource: LocalDataSource,
    private val dataStoreManager: DataStoreManager,
) : SpotifySyncRepository {

    // PKCE verifier lives here for the duration of the browser round-trip. Nothing survives a
    // process kill: if the process dies between opening the browser and the callback, the user
    // just re-taps "Se connecter" — Spotify mints a fresh code, so the stale verifier is harmless.
    @Volatile
    private var pendingCodeVerifier: String? = null

    override val oauthLoggedIn: Flow<Boolean> =
        dataStoreManager.spotifyOAuthAccessToken.map { it.isNotEmpty() }

    override fun startOAuthLogin(): String? {
        val verifier = SpotifyPkce.generateCodeVerifier()
        pendingCodeVerifier = verifier
        val challenge = SpotifyPkce.codeChallenge(verifier)
        val state = SpotifyPkce.randomState()
        return buildString {
            append("https://accounts.spotify.com/authorize")
            append("?client_id=").append(SpotifyClient.SPOTIFY_CLIENT_ID)
            append("&response_type=code")
            append("&redirect_uri=").append("simpmusic%3A%2F%2Fspotify-auth")
            append("&scope=").append("playlist-read-private%20playlist-read-collaborative")
            append("&code_challenge_method=S256")
            append("&code_challenge=").append(challenge)
            append("&state=").append(state)
        }
    }

    override suspend fun completeOAuthLogin(code: String): Boolean {
        val verifier = pendingCodeVerifier ?: run {
            Logger.e(TAG, "No pending PKCE verifier — start the OAuth flow first")
            return false
        }
        pendingCodeVerifier = null
        return try {
            val result = spotify.exchangeOAuthCode(code, verifier).getOrNull() ?: return false
            if (result.accessToken.isEmpty()) {
                Logger.e(TAG, "OAuth exchange failed: ${result.error} ${result.errorDescription}")
                return false
            }
            dataStoreManager.setSpotifyOAuthAccessToken(result.accessToken)
            if (result.refreshToken.isNotEmpty()) {
                dataStoreManager.setSpotifyOAuthRefreshToken(result.refreshToken)
            }
            dataStoreManager.setSpotifyOAuthExpiresAt(Clock.System.now().toEpochMilliseconds() + (result.expiresIn * 1000L))
            dataStoreManager.setSpotifyOAuthLoggedIn(true)
            Logger.d(TAG, "OAuth login complete")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "OAuth login failed: ${e.message}")
            false
        }
    }

    override suspend fun logout() {
        pendingCodeVerifier = null
        dataStoreManager.setSpotifyOAuthAccessToken("")
        dataStoreManager.setSpotifyOAuthRefreshToken("")
        dataStoreManager.setSpotifyOAuthExpiresAt(0L)
        dataStoreManager.setSpotifyOAuthLoggedIn(false)
    }

    private suspend fun getValidToken(): String? {
        val token = dataStoreManager.spotifyOAuthAccessToken.first()
        val expiresAt = dataStoreManager.spotifyOAuthExpiresAt.first()
        if (token.isNotEmpty() && expiresAt > Clock.System.now().toEpochMilliseconds()) return token
        val refreshToken = dataStoreManager.spotifyOAuthRefreshToken.first()
        if (refreshToken.isEmpty()) return null
        return try {
            val result = spotify.refreshOAuthToken(refreshToken).getOrNull() ?: return null
            if (result.accessToken.isEmpty()) {
                Logger.e(TAG, "Token refresh failed: ${result.error} ${result.errorDescription}")
                return null
            }
            dataStoreManager.setSpotifyOAuthAccessToken(result.accessToken)
            if (result.refreshToken.isNotEmpty()) {
                dataStoreManager.setSpotifyOAuthRefreshToken(result.refreshToken)
            }
            dataStoreManager.setSpotifyOAuthExpiresAt(Clock.System.now().toEpochMilliseconds() + (result.expiresIn * 1000L))
            result.accessToken
        } catch (e: Exception) {
            Logger.e(TAG, "Token refresh failed: ${e.message}")
            null
        }
    }

    override fun fetchPlaylists(): Flow<SpotifySyncProgress> = flow {
        emit(SpotifySyncProgress.Loading)
        val token = getValidToken()
        if (token == null) {
            emit(SpotifySyncProgress.Error("Spotify non connecté."))
            return@flow
        }
        val all = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        var total = Int.MAX_VALUE
        try {
            while (offset < total) {
                emit(SpotifySyncProgress.FetchingPlaylists(offset, total))
                spotify.getUserPlaylists(token, 50, offset).onSuccess {
                    total = it.total ?: 0
                    all.addAll(it.items)
                    offset += 50
                }.onFailure {
                    emit(SpotifySyncProgress.Error("Erreur: ${it.message}"))
                    return@flow
                }
            }
            emit(
                SpotifySyncProgress.PlaylistsReady(
                    all.map {
                        SpotifyPlaylistItem(
                            id = it.id,
                            name = it.name,
                            description = it.description,
                            trackCount = it.tracks?.total ?: 0,
                            imageUrl = it.images.firstOrNull()?.url,
                            ownerName = it.owner?.display_name,
                        )
                    },
                ),
            )
        } catch (e: Exception) {
            emit(SpotifySyncProgress.Error("Erreur: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    override fun importPlaylist(playlistId: String, playlistName: String): Flow<SpotifySyncProgress> = flow {
        emit(SpotifySyncProgress.Loading)
        val token = getValidToken()
        if (token == null) {
            emit(SpotifySyncProgress.Error("Spotify non connecté."))
            return@flow
        }
        try {
            val tracks = mutableListOf<SpotifyTrack>()
            var offset = 0
            var total = Int.MAX_VALUE
            while (offset < total) {
                emit(SpotifySyncProgress.Importing(playlistName, tracks.size, total))
                spotify.getPlaylistTracks(token, playlistId, 100, offset).onSuccess {
                    total = it.total ?: 0
                    it.items.forEach { i ->
                        i.track?.let { t ->
                            if (t.id.isNotEmpty() && !(i.isLocal ?: false)) tracks.add(t)
                        }
                    }
                    offset += 100
                }.onFailure {
                    emit(SpotifySyncProgress.Error("Erreur: ${it.message}"))
                    return@flow
                }
            }
            val songs = mutableListOf<SongEntity>()
            val vids = mutableListOf<String>()
            var skip = 0
            tracks.forEachIndexed { idx, t ->
                emit(SpotifySyncProgress.Importing(playlistName, idx + 1, tracks.size))
                try {
                    val r = youtube.search("${t.artists.joinToString(" ") { it.name }} ${t.name}", YouTube.SearchFilter.FILTER_SONG)
                    val first = r.getOrNull()?.items?.firstOrNull()
                    if (first is SongItem) {
                        songs.add(
                            SongEntity(
                                videoId = first.id,
                                albumId = null,
                                albumName = null,
                                artistId = first.artists.mapNotNull { it.id }.takeIf { it.isNotEmpty() },
                                artistName = first.artists.map { it.name }.takeIf { it.isNotEmpty() },
                                duration = first.duration?.toString() ?: "",
                                durationSeconds = first.duration ?: 0,
                                isAvailable = true,
                                isExplicit = first.explicit,
                                likeStatus = "",
                                thumbnails = first.thumbnail,
                                title = first.title,
                                videoType = "MUSIC_VIDEO_TYPE_ATV",
                                category = null,
                                resultType = null,
                            ),
                        )
                        vids.add(first.id)
                    } else {
                        skip++
                    }
                } catch (_: Exception) {
                    skip++
                }
            }
            if (songs.isNotEmpty()) localDataSource.insertSongs(songs)
            localDataSource.insertLocalPlaylistWithTracks(
                LocalPlaylistEntity(title = playlistName, thumbnail = null, tracks = vids),
                vids,
            )
            emit(SpotifySyncProgress.PlaylistImported(playlistName, songs.size, skip))
        } catch (e: Exception) {
            emit(SpotifySyncProgress.Error("Erreur: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    override fun importAllPlaylists(playlists: List<Pair<String, String>>): Flow<SpotifySyncProgress> = flow {
        var imp = 0
        var sk = 0
        playlists.forEachIndexed { index, (id, name) ->
            emit(SpotifySyncProgress.Importing(name, index, playlists.size))
            importPlaylist(id, name).collect { p ->
                when (p) {
                    is SpotifySyncProgress.PlaylistImported -> {
                        imp += p.tracksImported
                        sk += p.tracksSkipped
                    }

                    is SpotifySyncProgress.Error -> emit(p)

                    else -> Unit
                }
            }
        }
        emit(SpotifySyncProgress.AllImported(playlists.size, imp, sk))
    }.flowOn(Dispatchers.IO)
}

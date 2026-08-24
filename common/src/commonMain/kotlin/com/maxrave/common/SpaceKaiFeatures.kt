package com.maxrave.common

/**
 * SpaceKai feature registry.
 *
 * SpaceKai is a layer over SimpMusic (upstream). Every feature SpaceKai adds on top of the
 * upstream engine is listed here as a compile-time flag. Flags are `true` in this build;
 * they exist so that:
 *
 *  1. a SpaceKai feature can be switched off without hunting through upstream code,
 *  2. a future upstream sync can see at a glance what is SpaceKai-owned (see docs/UPSTREAM.md),
 *  3. R8 can strip a disabled feature's UI branch entirely (constants are inlined).
 *
 * A feature flag gates the feature's *entry points* (UI rows, deep links, nav destinations).
 * The feature's own implementation lives in identifiable SpaceKai files (e.g.
 * `SpotifySync*`, `SpotifyPkce`) so upstream merges never touch it directly.
 */
object SpaceKaiFeatures {
    /** Spotify playlist import (OAuth PKCE) — Settings → Spotify → "Importer vos playlists Spotify". */
    const val SPOTIFY_SYNC: Boolean = true

    /** Haptic feedback (vibration) with intensity control — Settings → Interface. */
    const val HAPTICS: Boolean = true

    /** Minimalistic bottom navigation bar style. */
    const val MINIMALISTIC_NAVIGATION: Boolean = true

    /** Customizable navigation bar style selector (translucent / liquid glass / minimalistic). */
    const val CUSTOM_NAVIGATION: Boolean = true

    /** Dynamic color fixes + custom theme color. */
    const val DYNAMIC_COLOR: Boolean = true

    /** Phone landscape routes the Now Playing to the dedicated full-screen player. */
    const val LANDSCAPE_PLAYER: Boolean = true

    /** Update checker pointed at SpaceKai releases (N7T0-OF/Sankamusic), not SimpMusic. */
    const val SPACEKAI_UPDATE_CHECKER: Boolean = true

    // Reserved / planned flags (off until implemented):
    const val CUSTOM_PLAYER_INFO: Boolean = false
    const val DOWNLOAD_WIFI_ONLY: Boolean = false
}

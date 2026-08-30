package com.maxrave.spotify

import kotlin.random.Random
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * PKCE (RFC 7636) helpers for the Spotify OAuth authorization-code flow.
 *
 * The verifier is a random 43–128 char base64url string; the challenge is its SHA-256,
 * base64url-encoded without padding, exactly as Spotify's `code_challenge_method=S256` expects.
 */
object SpotifyPkce {
    private const val VERIFIER_LENGTH = 64
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** Generates a fresh random code verifier (base64url alphabet, 64 chars). */
    fun generateCodeVerifier(): String =
        buildString(VERIFIER_LENGTH) {
            repeat(VERIFIER_LENGTH) {
                append(ALPHABET[Random.nextInt(ALPHABET.length)])
            }
        }

    /** Computes the S256 code challenge for a verifier: base64url(sha256(verifier)) without padding. */
    fun codeChallenge(verifier: String): String =
        verifier
            .encodeUtf8()
            .sha256()
            .base64Url()
            .trimEnd('=')

    /** Random bytes → base64url without padding; used to build the state parameter. */
    fun randomState(): String =
        Random
            .nextBytes(16)
            .toByteString()
            .base64Url()
            .trimEnd('=')
}

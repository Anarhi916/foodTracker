package com.nutrition.tracker.data.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.nutrition.tracker.BuildConfig
import com.nutrition.tracker.data.api.ApiClient
import com.nutrition.tracker.data.api.GoogleCodeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class AuthManager(
    private val tokenStore: TokenStore
) {
    private val _authState = MutableStateFlow(tokenStore.isSignedIn)
    val authState: StateFlow<Boolean> = _authState

    // true while the Google auth code is being exchanged for a session on the backend.
    // The Custom Tab covers the app during the browser phase; this covers the window AFTER
    // the tab closes (redirect delivered) until authState flips — otherwise the login screen
    // shows for the whole token-exchange round-trip.
    private val _authInProgress = MutableStateFlow(false)
    val authInProgress: StateFlow<Boolean> = _authInProgress

    // true → account deleted from another device; the UI shows a notification and resets.
    private val _accountDeletedNotice = MutableStateFlow(false)
    val accountDeletedNotice: StateFlow<Boolean> = _accountDeletedNotice

    fun setAccountDeletedNotice(v: Boolean) { _accountDeletedNotice.value = v }

    @Volatile private var pendingNonce: String? = null
    @Volatile private var pendingCodeVerifier: String? = null

    // Apple redirects to our HTTPS server callback (custom schemes are not allowed by Apple).
    // Google uses the iOS reversed-client-ID scheme (public client — no secret needed for PKCE).
    private val appleRedirectScheme = BuildConfig.OAUTH_REDIRECT_SCHEME
    private val googleRedirectScheme = "com.googleusercontent.apps.${BuildConfig.GOOGLE_IOS_CLIENT_ID_SHORT}"
    private val googleRedirectUri = "$googleRedirectScheme:/oauth2redirect"
    // Backend origin without trailing slash, e.g. https://api.nutritiontracker.uk
    private val BACKEND_ORIGIN = BuildConfig.BACKEND_BASE_URL.trimEnd('/')

    fun refreshAuthState() { _authState.value = tokenStore.isSignedIn }

    fun launchGoogle(context: Context) {
        val verifier = randomBase64Url(32)
        val challenge = sha256Base64url(verifier)
        pendingCodeVerifier = verifier
        val url = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.GOOGLE_IOS_CLIENT_ID)
            .appendQueryParameter("redirect_uri", googleRedirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "openid email")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        openTab(context, url)
    }

    fun launchApple(context: Context) {
        // Apple does NOT allow custom-scheme redirects — the Return URL must be our HTTPS
        // server callback. The server exchanges the code, issues our session, and deep-links
        // back into the app at com.nutrition.tracker:/apple-auth with the tokens.
        val nonce = randomNonce()
        pendingNonce = nonce
        val url = Uri.parse("https://appleid.apple.com/auth/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.APPLE_SERVICES_ID)
            .appendQueryParameter("redirect_uri", "$BACKEND_ORIGIN/v1/auth/apple/callback")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "email")
            .appendQueryParameter("response_mode", "form_post")
            .appendQueryParameter("nonce", nonce)
            .build()
        openTab(context, url)
    }

    private fun openTab(context: Context, url: Uri) {
        CustomTabsIntent.Builder().build().launchUrl(context, url)
    }

    suspend fun handleRedirect(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false

        // Apple flow: server already exchanged the code and deep-links back with tokens.
        if (scheme == appleRedirectScheme && uri.path?.contains("apple-auth") == true) {
            val access = uri.getQueryParameter("access")
            val refresh = uri.getQueryParameter("refresh")
            return if (!access.isNullOrBlank() && !refresh.isNullOrBlank()) {
                tokenStore.save(access, refresh)
                _authState.value = true
                true
            } else false
        }

        // Google flow: we receive the auth code and exchange it via the backend (PKCE).
        if (scheme != googleRedirectScheme) return false
        val code = uri.getQueryParameter("code") ?: return false
        _authInProgress.value = true
        return try {
            val verifier = pendingCodeVerifier ?: return false
            val resp = ApiClient.backendApi.authGoogleCode(GoogleCodeRequest(
                code = code,
                codeVerifier = verifier,
                redirectUri = googleRedirectUri,
                clientId = BuildConfig.GOOGLE_IOS_CLIENT_ID
            ))
            val tokens = resp.body()
            if (resp.isSuccessful && tokens != null && tokens.accessToken.isNotBlank()) {
                tokenStore.save(tokens.accessToken, tokens.refreshToken)
                _authState.value = true
                true
            } else false
        } catch (_: Exception) {
            false
        } finally {
            pendingNonce = null
            pendingCodeVerifier = null
            _authInProgress.value = false
        }
    }

    suspend fun signOut() {
        val refresh = tokenStore.refreshToken
        if (refresh != null) {
            try { ApiClient.backendApi.logout(com.nutrition.tracker.data.api.RefreshRequest(refresh)) } catch (_: Exception) {}
        }
        tokenStore.clear()
        _authState.value = false
    }

    suspend fun deleteAccount() {
        val access = tokenStore.accessToken
        if (access != null) {
            try { ApiClient.backendApi.deleteAccount("Bearer $access") } catch (_: Exception) {}
        }
        tokenStore.clear()
        _authState.value = false
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(24); SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomBase64Url(byteLen: Int): String {
        val bytes = ByteArray(byteLen); SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Base64url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}


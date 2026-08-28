package com.nutrition.tracker.data.auth

import android.content.Context
import android.util.Base64
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityToken
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google Play Integrity (Android attestation), mirroring backend/src/services/playIntegrity.js.
 *
 * Per protected request we mint a fresh Standard integrity token bound to a random nonce via
 * requestHash = SHA256hex(nonce). The backend decodes the token (decodeIntegrityToken), checks
 * the verdict, and re-derives SHA256hex(X-Integrity-Nonce) to confirm the token belongs to THIS
 * request. Play Integrity carries no stable device id, so we also send X-Device-Id purely for
 * server-side rate-limiting.
 *
 * Best-effort: if the cloud project number is unset (0) or the Play Integrity API fails (no Play
 * services, emulator, offline), requestHeaders() returns null and the caller proceeds on
 * X-Dev-Auth — which only a dev-mode backend accepts. A prod backend requires the token.
 */
object IntegrityService {
    @Volatile private var appContext: Context? = null
    @Volatile private var cloudProjectNumber: Long = 0L
    @Volatile private var deviceId: String? = null

    // The warm-up provider is expensive to create; cache it and rebuild only if it goes stale.
    @Volatile private var tokenProvider: StandardIntegrityTokenProvider? = null
    private val prepareLock = Any()

    fun init(context: Context, cloudProjectNumber: Long, deviceId: String) {
        appContext = context.applicationContext
        this.cloudProjectNumber = cloudProjectNumber
        this.deviceId = deviceId
    }

    fun deviceId(): String? = deviceId

    /**
     * Attestation headers for a protected request, or null if unavailable (best-effort).
     * BLOCKING (network + Play services) — must be called off the main thread. OkHttp
     * interceptor/authenticator threads are fine.
     */
    fun requestHeaders(): Map<String, String>? {
        val provider = provider() ?: return null
        return try {
            val nonce = randomNonce()
            val requestHash = sha256Hex(nonce)
            val token: StandardIntegrityToken = Tasks.await(
                provider.request(
                    StandardIntegrityTokenRequest.builder()
                        .setRequestHash(requestHash)
                        .build()
                )
            )
            buildMap {
                put("X-Integrity-Token", token.token())
                put("X-Integrity-Nonce", nonce)
                deviceId?.let { put("X-Device-Id", it) }
            }
        } catch (e: Exception) {
            tokenProvider = null
            null
        }
    }

    private fun provider(): StandardIntegrityTokenProvider? {
        tokenProvider?.let { return it }
        synchronized(prepareLock) {
            tokenProvider?.let { return it }
            val ctx = appContext ?: return null
            if (cloudProjectNumber == 0L) return null
            // Suppress the Play Services "Something went wrong" system dialog: if Play Services
            // is unavailable or outdated, return null silently instead of letting the library
            // show its own error UI as a side-effect of a failed prepareIntegrityToken call.
            val gpsStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx)
            if (gpsStatus != ConnectionResult.SUCCESS) return null
            return try {
                val manager = IntegrityManagerFactory.createStandard(ctx)
                val prepared = Tasks.await(
                    manager.prepareIntegrityToken(
                        PrepareIntegrityTokenRequest.builder()
                            .setCloudProjectNumber(cloudProjectNumber)
                            .build()
                    )
                )
                tokenProvider = prepared
                prepared
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

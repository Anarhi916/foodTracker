package com.nutrition.tracker.data.api

import android.os.Build
import com.nutrition.tracker.BuildConfig

/**
 * Resolves the backend base URL at runtime.
 *
 * The corporate network on the dev machine blocks our production domain
 * (api.nutritiontracker.uk) at the TLS/SNI layer, so the emulator — which routes
 * through that machine — can never complete a request to it. To test on the emulator
 * we run an SSH tunnel to the server's plain-HTTP port 3000 (Node listens there
 * directly; Caddy only fronts TLS), reachable from the emulator as 10.0.2.2:3000.
 *
 * This override is DEBUG-ONLY and only kicks in when running on an emulator, so the
 * SAME debug APK still hits production when installed on a real phone. Release builds
 * always use the production URL from BuildConfig.
 *
 * To use it: start the tunnel on the host —
 *   ssh -N -L 3000:127.0.0.1:3000 root@169.58.153.252
 * then run the debug app on the emulator.
 */
object BackendConfig {
    /** Trailing-slash base URL, e.g. "http://10.0.2.2:3000/". */
    val baseUrl: String
        get() = if (BuildConfig.DEBUG && isEmulator) EMULATOR_TUNNEL_URL
                else BuildConfig.BACKEND_BASE_URL

    /** Origin without trailing slash, e.g. "http://10.0.2.2:3000". */
    val origin: String
        get() = baseUrl.trimEnd('/')

    // 10.0.2.2 is the host loopback as seen from the Android emulator; the SSH tunnel
    // forwards it to the server's port 3000.
    private const val EMULATOR_TUNNEL_URL = "http://10.0.2.2:3000/"

    private val isEmulator: Boolean by lazy {
        (Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.PRODUCT.contains("sdk")
            || Build.PRODUCT.contains("emulator")
            || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")))
    }
}

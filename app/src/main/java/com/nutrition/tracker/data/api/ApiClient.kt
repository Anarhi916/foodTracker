package com.nutrition.tracker.data.api

import com.google.gson.GsonBuilder
import com.nutrition.tracker.BuildConfig
import com.nutrition.tracker.data.auth.TokenStore
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private val gson = GsonBuilder().setLenient().create()

    // TokenStore is injected from NutritionApp.onCreate(). Bearer isn't set before initialization.
    @Volatile
    private var tokenStore: TokenStore? = null
    // "Session expired" callback (refresh failed) — the app signs the user out.
    @Volatile
    private var onSessionExpired: (() -> Unit)? = null
    // "Account deleted" callback (backend returned account_deleted) — wipe + logout.
    @Volatile
    private var onAccountDeleted: (() -> Unit)? = null

    fun init(store: TokenStore, onExpired: () -> Unit, onDeleted: () -> Unit = {}) {
        tokenStore = store
        onSessionExpired = onExpired
        onAccountDeleted = onDeleted
    }

    // Catches 401 with body {"error":"account_deleted"} → wipe local data + logout.
    // Placed BEFORE the authenticator: if the account is deleted, a refresh is pointless.
    private val accountDeletedInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 401 && !isAuthPath(response.request)) {
            val peeked = response.peekBody(1024).string()
            if (peeked.contains("\"account_deleted\"")) {
                onAccountDeleted?.invoke()
            }
        }
        response
    }

    // Bearer from TokenStore for every request to the backend (except /v1/auth/*).
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val access = tokenStore?.accessToken
        val req = if (access != null && !isAuthPath(original)) {
            original.newBuilder().header("Authorization", "Bearer $access").build()
        } else original
        chain.proceed(req)
    }

    // On 401 — refresh the session once with the refresh token and retry the request.
    private val refreshAuthenticator = Authenticator { _: Route?, response: Response ->
        val store = tokenStore ?: return@Authenticator null
        if (isAuthPath(response.request)) return@Authenticator null      // don't refresh the auth requests themselves
        if (responseCount(response) >= 2) return@Authenticator null      // already tried
        val refresh = store.refreshToken ?: return@Authenticator null

        val newAccess = synchronized(this) {
            // Another thread may have already refreshed — check whether access changed.
            val current = store.accessToken
            val sentAccess = response.request.header("Authorization")?.removePrefix("Bearer ")
            if (current != null && current != sentAccess) {
                current
            } else {
                tryRefreshBlocking(refresh, store)
            }
        } ?: run {
            onSessionExpired?.invoke()
            return@Authenticator null
        }

        response.request.newBuilder().header("Authorization", "Bearer $newAccess").build()
    }

    // Synchronous refresh via a separate minimal client (no interceptors).
    private fun tryRefreshBlocking(refresh: String, store: TokenStore): String? {
        return try {
            val body = gson.toJson(RefreshRequest(refresh))
            val client = OkHttpClient()
            val req = Request.Builder()
                .url(BackendConfig.origin + "/v1/auth/refresh")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val tokens = gson.fromJson(resp.body?.string(), TokenResponse::class.java)
                if (tokens.accessToken.isBlank()) return null
                store.save(tokens.accessToken, tokens.refreshToken)
                tokens.accessToken
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isAuthPath(req: Request): Boolean =
        req.url.encodedPath.contains("/v1/auth/")

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var count = 1
        while (r?.priorResponse != null) { count++; r = r.priorResponse }
        return count
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(accountDeletedInterceptor)
            .authenticator(refreshAuthenticator)
            .apply {
                // Log request bodies ONLY in debug — otherwise tokens and
                // profile/food data leak into logcat in production.
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
            }
            .build()
    }

    // Our backend proxy. Base URL resolved at runtime (BackendConfig: prod, or the
    // SSH-tunnel URL when running on an emulator in debug).
    val backendApi: BackendApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BackendConfig.baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(BackendApiService::class.java)
    }

    // OpenFoodFacts — stays on the client (barcode; its own IP). User-Agent is required.
    val openFoodFactsApi: OpenFoodFactsApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://world.openfoodfacts.org/")
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", "NutritionTracker/1.0 (Android)")
                            .build()
                        chain.proceed(request)
                    }
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(OpenFoodFactsApiService::class.java)
    }
}

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

    // TokenStore внедряется из NutritionApp.onCreate(). До инициализации Bearer не ставится.
    @Volatile
    private var tokenStore: TokenStore? = null
    // Колбэк «сессия протухла» (refresh не удался) — приложение разлогинивает пользователя.
    @Volatile
    private var onSessionExpired: (() -> Unit)? = null

    fun init(store: TokenStore, onExpired: () -> Unit) {
        tokenStore = store
        onSessionExpired = onExpired
    }

    // Bearer из TokenStore для каждого запроса к backend (кроме /v1/auth/*).
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val access = tokenStore?.accessToken
        val req = if (access != null && !isAuthPath(original)) {
            original.newBuilder().header("Authorization", "Bearer $access").build()
        } else original
        chain.proceed(req)
    }

    // На 401 — один раз обновляем сессию refresh-токеном и повторяем запрос.
    private val refreshAuthenticator = Authenticator { _: Route?, response: Response ->
        val store = tokenStore ?: return@Authenticator null
        if (isAuthPath(response.request)) return@Authenticator null      // не рефрешим сами auth-запросы
        if (responseCount(response) >= 2) return@Authenticator null      // уже пробовали
        val refresh = store.refreshToken ?: return@Authenticator null

        val newAccess = synchronized(this) {
            // Возможно, другой поток уже обновил — проверим, не сменился ли access.
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

    // Синхронный refresh через отдельный минимальный клиент (без интерцепторов).
    private fun tryRefreshBlocking(refresh: String, store: TokenStore): String? {
        return try {
            val body = gson.toJson(RefreshRequest(refresh))
            val client = OkHttpClient()
            val req = Request.Builder()
                .url(BuildConfig.BACKEND_BASE_URL.trimEnd('/') + "/v1/auth/refresh")
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
            .authenticator(refreshAuthenticator)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    // Наш backend-прокси. Base URL из BuildConfig (dev: http://10.0.2.2:3000/).
    val backendApi: BackendApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(BackendApiService::class.java)
    }

    // OpenFoodFacts — остаётся на клиенте (штрихкод; свой IP). User-Agent обязателен.
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

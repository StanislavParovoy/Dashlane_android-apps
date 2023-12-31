package com.dashlane.network.inject

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.dashlane.network.tools.CloudflareHeaderInterceptor
import com.dashlane.network.tools.MoreDetailedExceptionRequestInterceptor
import com.dashlane.network.webservices.DashlaneUrls.URL_API
import com.dashlane.server.api.Authorization
import com.dashlane.server.api.ConnectivityCheck
import com.dashlane.server.api.DashlaneApi
import com.dashlane.server.api.DashlaneApiClient
import com.dashlane.server.api.DashlaneTime
import com.dashlane.server.api.LongPollingInterceptor
import com.dashlane.server.api.UserAgent
import com.dashlane.server.api.analytics.AnalyticsApi
import com.dashlane.server.api.analytics.AnalyticsApiClient
import dagger.Module
import dagger.Provides
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Module
class HttpModule {

    @Singleton
    @Provides
    fun getOkHttpClient(userAgent: UserAgent): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(MoreDetailedExceptionRequestInterceptor())
            .addInterceptor(userAgent.interceptor)
            .addInterceptor(LongPollingInterceptor())
            .addInterceptor(CloudflareHeaderInterceptor())
            
            .addInterceptor(
                HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
                    override fun log(message: String) {
                        Log.v("HTTP", message)
                    }
                }).apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
            .build()
    }

    @Provides
    fun getHttpCallFactory(client: OkHttpClient): Call.Factory = client

    @Singleton
    @Provides
    fun getDashlaneApi(
        callFactory: Call.Factory,
        authorization: Authorization.App,
        connectivityCheck: ConnectivityCheck
    ) =
        DashlaneApi(
            client = DashlaneApiClient(
                callFactory = callFactory,
                connectivityCheck = connectivityCheck,
                host = URL_API
            ),
            appAuthorization = authorization,
            clock = SystemClockElapsedRealTime()
        )

    @Singleton
    @Provides
    fun getAnalyticsApi(
        dashlaneApi: DashlaneApi,
        okHttpClient: OkHttpClient,
        connectivityCheck: ConnectivityCheck,
        analyticsAuthorization: Authorization.Analytics
    ):
        AnalyticsApi {
        return AnalyticsApi(
            client = AnalyticsApiClient(
                callFactory = okHttpClient.newBuilder()
                    
                    
                    .readTimeout(3, TimeUnit.MINUTES)
                    .build(),
                connectivityCheck = connectivityCheck
            ),
            analyticsAuthorization = analyticsAuthorization,
            dashlaneApi = dashlaneApi
        )
    }

    @Singleton
    @Provides
    fun getConnectivityCheck(connectivityManagerProvider: Provider<ConnectivityManager>): ConnectivityCheck {
        return object : ConnectivityCheck {

            private val connectivityManager: ConnectivityManager =
                connectivityManagerProvider.get()

            override val isOnline: Boolean
                get() = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                    @Suppress("DEPRECATION")
                    connectivityManager.allNetworks.any { it.hasInternet }
                } else {
                    connectivityManager.activeNetwork?.hasInternet ?: false
                }

            private val Network?.hasInternet
                get() = networkCapabilities.let { it != null && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }

            private val Network?.networkCapabilities
                get() =
                    try {
                        connectivityManager.getNetworkCapabilities(this)
                    } catch (e: SecurityException) {
                        
                        
                        null
                    }
        }
    }

    @Provides
    fun getDashlaneTime(dashlaneApi: DashlaneApi): DashlaneTime =
        dashlaneApi.dashlaneTime

    private class SystemClockElapsedRealTime : SimpleClock(ZoneOffset.UTC) {
        override fun millis(): Long = android.os.SystemClock.elapsedRealtime()
    }

    abstract class SimpleClock(private val zone: ZoneId) : Clock() {

        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock =
            object : SimpleClock(zone) {
                override fun millis(): Long {
                    return this@SimpleClock.millis()
                }
            }

        abstract override fun millis(): Long
    }
}

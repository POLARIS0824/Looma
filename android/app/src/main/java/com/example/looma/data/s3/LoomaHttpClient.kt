package com.example.looma.data.s3

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

internal fun createLoomaHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    expectSuccess = false
    engine {
        config {
            connectTimeout(15_000, TimeUnit.MILLISECONDS)
            readTimeout(60_000, TimeUnit.MILLISECONDS)
            writeTimeout(60_000, TimeUnit.MILLISECONDS)
            callTimeout(0, TimeUnit.MILLISECONDS)
            followRedirects(false)
            followSslRedirects(false)
        }
    }
}

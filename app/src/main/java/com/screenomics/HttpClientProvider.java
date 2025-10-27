package com.screenomics;

import android.content.Context;

import com.screenomics.HttpSignatureInterceptor;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public final class HttpClientProvider {
    private static volatile OkHttpClient INSTANCE;

    private HttpClientProvider() {}

    public static OkHttpClient get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (HttpClientProvider.class) {
                if (INSTANCE == null) {
                    INSTANCE = new OkHttpClient.Builder()
                            .addInterceptor(new HttpSignatureInterceptor(ctx))
                            .readTimeout(120, TimeUnit.SECONDS)
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(120, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}

package com.screenomics;

import android.content.Context;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
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
                            // Prefer IPv4 first to reduce IPv6-path connect stalls observed in field logs.
                            .dns(new Ipv4FirstDns())
                            .readTimeout(Constants.REQ_TIMEOUT_SECS, TimeUnit.SECONDS)
                            .connectTimeout(Constants.CONNECT_TIMEOUT_SECS, TimeUnit.SECONDS)
                            .writeTimeout(Constants.REQ_TIMEOUT_SECS, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    private static final class Ipv4FirstDns implements Dns {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> all = Dns.SYSTEM.lookup(hostname);
            List<InetAddress> ipv4 = new ArrayList<>();
            List<InetAddress> other = new ArrayList<>();
            for (InetAddress address : all) {
                if (address instanceof Inet4Address) {
                    ipv4.add(address);
                } else {
                    other.add(address);
                }
            }
            ipv4.addAll(other);
            return ipv4;
        }
    }
}

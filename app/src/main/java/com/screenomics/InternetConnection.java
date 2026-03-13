package com.screenomics;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

public class InternetConnection {

    /**
     * CHECK WHETHER INTERNET CONNECTION IS AVAILABLE OR NOT
     */
    public static boolean checkWiFiConnection(Context context) {
        final ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr != null) {
            Network active = connMgr.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities caps = connMgr.getNetworkCapabilities(active);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    Log.d("SCREENOMICS", "Has WiFi");
                    return true;
                }
            }
        }
        Log.d("SCREENOMICS", "NO WiFi");
        return false;
    }

    public static boolean checkMobileDataConnection(Context context) {
        final ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr != null) {
            Network active = connMgr.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities caps = connMgr.getNetworkCapabilities(active);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    Log.d("SCREENOMICS", "Has Cellular");
                    return true;
                }
            }
        }
        Log.d("SCREENOMICS", "NO Cellular");
        return false;
    }
    public static boolean isConnected(Context context) {
        final ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr != null) {
            Network activeNetwork = connMgr.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities caps = connMgr.getNetworkCapabilities(activeNetwork);
                return caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
        }
        return false;
    }

    public static String getState(Context context) {
        final ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr != null) {
            Network activeNetwork = connMgr.getActiveNetwork();
            if (activeNetwork == null) return "N";
            NetworkCapabilities caps = connMgr.getNetworkCapabilities(activeNetwork);
            if (caps == null) return "N";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "W";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "D";
        }
        return "U";
    }
}
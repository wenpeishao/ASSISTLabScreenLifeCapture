package com.screenomics;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Network;
import android.net.NetworkCapabilities;

public class InternetConnection {

    /**
     * CHECK WHETHER INTERNET CONNECTION IS AVAILABLE OR NOT
     */
    public static boolean checkWiFiConnection(Context context) {
        final ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr != null) {
            //NetworkInfo activeNetworkInfo = connMgr.getActiveNetworkInfo();
            //if (activeNetworkInfo != null && activeNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI) return true;
            Network[] networks = connMgr.getAllNetworks();
            for(int i = 0; i < networks.length; i++) {
                NetworkCapabilities networkCapabilities = connMgr.getNetworkCapabilities(networks[i]);
                if(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){
                    return true;
                }
            }
        }

        return false;
    }
    public static boolean isConnected(Context context) {
        final ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr != null) {
            NetworkInfo activeNetworkInfo = connMgr.getActiveNetworkInfo();
            if (activeNetworkInfo != null) return true;
        }
        return false;
    }

    public static String getState(Context context) {
        final ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr != null) {
            NetworkInfo activeNetworkInfo = connMgr.getActiveNetworkInfo();
            if (activeNetworkInfo == null) return "N";
            if (activeNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI) return "W";
            if (activeNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE) return "D";
        }
        return "U";
    }
}
package com.sclimin.screenshare;

import android.app.Application;
import android.app.Service;

public class App extends Application {

    public interface ServerStateListener {
        void onServerStateChange();
    }

    public static ServerStateListener listener;

    public static Service service;

    public static String ip;

    @Override
    public void onCreate() {
        super.onCreate();
    }
}

package com.icarme.lyrics;

import android.app.Application;

/** 轻量全局上下文，供无 Service 场景读取（如 advertisedName） */
public class IcarApp extends Application {
    private static IcarApp app;
    @Override public void onCreate() { super.onCreate(); app = this; }
    public static IcarApp get() { return app; }
}

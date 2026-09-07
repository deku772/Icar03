package com.icarme.lyrics.phone;

import android.app.Application;

/** 轻量全局上下文（供主界面在服务未运行时读写偏好） */
public class IcarPhoneApp extends Application {
    private static IcarPhoneApp app;
    @Override public void onCreate() { super.onCreate(); app = this; }
    public static IcarPhoneApp get() { return app; }
}
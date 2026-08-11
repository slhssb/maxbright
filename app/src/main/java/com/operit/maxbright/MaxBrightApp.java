package com.operit.maxbright;

import android.app.Application;

import rikka.shizuku.Shizuku;

/**
 * 应用入口：初始化 Shizuku 连接监听与权限状态，并把状态同步到 PrivilegeManager。
 *
 * Shizuku API 13.1.5：
 * - addBinderReceivedListenerSticky：服务连接回调（即使已连接也会立即回调一次）
 * - addBinderDeadListener：服务断开回调
 * - Shizuku.getUid()：获取 Shizuku 服务运行的 uid（0=root，2000=shell）
 * - Shizuku.checkSelfPermission()：本应用是否已被授予 Shizuku 权限
 *
 * Root 探测由 PrivilegeManager 按需惰性执行，无需在启动时做。
 */
public class MaxBrightApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PrivilegeManager.attach(this);

        try {
            Shizuku.addBinderReceivedListenerSticky(() -> {
                int uid = -1;
                try {
                    uid = Shizuku.getUid();
                } catch (Throwable ignored) {
                }
                PrivilegeManager.onShizukuConnected(uid);
                refreshShizukuPermission();
            });
            Shizuku.addBinderDeadListener(() -> {
                PrivilegeManager.onShizukuDisconnected();
                PrivilegeManager.onShizukuPermissionChanged(false);
            });
            // 权限授予结果（用户点击授权弹窗后回调）
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) ->
                    refreshShizukuPermission());
            refreshShizukuPermission();
        } catch (Throwable t) {
            // Shizuku 未安装或初始化失败时静默降级，仅依赖 Root
        }
    }

    /** 刷新 Shizuku 授权状态到 PrivilegeManager。 */
    private void refreshShizukuPermission() {
        try {
            boolean granted = Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            PrivilegeManager.onShizukuPermissionChanged(granted);
        } catch (Throwable ignored) {
        }
    }
}
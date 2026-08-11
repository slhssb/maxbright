package com.operit.maxbright;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 硬件背光操作与开关状态持久化。
 *
 * 节点路径与最大值由 PanelSpec 运行时自动探测，兼容不同机型。
 * 系统设置通道通常无法达到硬件最大值，
 * 因此通过 Root 直写硬件节点实现真正的硬件最大亮度。
 */
public final class BrightnessConfig {

    private static final String PREFS = "max_bright_prefs";

    private BrightnessConfig() {
    }

    /** 读取指定屏幕的硬件节点当前值。 */
    public static int readNode(String node) {
        PrivilegedShell.Result r = PrivilegedShell.run("cat " + node);
        if (!r.ok()) return -1;
        try {
            return Integer.parseInt(r.stdout.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 读取任意 settings system 整型设置，失败返回 def。 */
    public static int getSetting(String key, int def) {
        PrivilegedShell.Result r = PrivilegedShell.run("settings get system " + key);
        try {
            return Integer.parseInt(r.stdout.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 读取面板背光电源状态 bl_power：0=点亮，非0=熄灭/休眠。失败返回 -1。 */
    public static int readBlPower(String node) {
        String dir = node.substring(0, node.lastIndexOf('/'));
        PrivilegedShell.Result r = PrivilegedShell.run("cat " + dir + "/bl_power");
        try {
            return Integer.parseInt(r.stdout.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 直写硬件节点到最大值。
     * 仅当写命令本身失败（无 Root / 节点不可写）时返回 false；
     * 写入后若回读暂时偏低（系统亮度服务可能仍在应用设置），短暂重试数次；
     * 仍偏低也视为成功，交由守护服务巡检补写。
     */
    public static boolean writeNodeMax(String node, int max) {
        PrivilegedShell.Result r = PrivilegedShell.run("echo " + max + " > " + node + " 2>/dev/null");
        if (!r.ok()) return false;
        for (int i = 0; i < 4; i++) {
            int v = readNode(node);
            if (v >= max - 1) return true;
            PrivilegedShell.run("echo " + max + " > " + node + " 2>/dev/null");
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return true;
    }

    /** 系统当前亮度设置（settings system screen_brightness，0~255）。 */
    public static int getSystemBrightness() {
        PrivilegedShell.Result r = PrivilegedShell.run("settings get system screen_brightness");
        try {
            return Integer.parseInt(r.stdout.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 自动亮度模式：0=手动，1=自动。 */
    public static int getBrightnessMode() {
        PrivilegedShell.Result r = PrivilegedShell.run("settings get system screen_brightness_mode");
        try {
            return Integer.parseInt(r.stdout.trim());
        } catch (Exception e) {
            return 1;
        }
    }

    public static void setSystemBrightness(int value) {
        PrivilegedShell.run("settings put system screen_brightness " + value);
    }

    public static void setBrightnessMode(int mode) {
        PrivilegedShell.run("settings put system screen_brightness_mode " + mode);
    }

    // ---------- 状态持久化 ----------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 快照持久化（开启前现场：亮度值、亮度模式、背屏超时） ----------

    /** 记录开启磁贴前的现场，用于关闭时恢复。timeout 为背屏超时原值（主屏传 -1 表示不适用）。 */
    public static void saveSnapshot(Context ctx, String key, int brightness, int mode, int timeout) {
        prefs(ctx).edit()
                .putInt(key + "_brightness", brightness)
                .putInt(key + "_mode", mode)
                .putInt(key + "_timeout", timeout)
                .apply();
    }

    /** 返回 [brightness, mode, timeout]；无记录时返回 null。 */
    public static int[] loadSnapshot(Context ctx, String key) {
        SharedPreferences p = prefs(ctx);
        int b = p.getInt(key + "_brightness", -1);
        int m = p.getInt(key + "_mode", -1);
        if (b < 0 || m < 0) return null;
        return new int[]{b, m, p.getInt(key + "_timeout", -1)};
    }

    public static void clearSnapshot(Context ctx, String key) {
        prefs(ctx).edit()
                .remove(key + "_brightness")
                .remove(key + "_mode")
                .remove(key + "_timeout")
                .apply();
    }

    public static void setActive(Context ctx, String key, boolean active) {
        prefs(ctx).edit().putBoolean(key + "_active", active).apply();
    }

    public static boolean isActive(Context ctx, String key) {
        return prefs(ctx).getBoolean(key + "_active", false);
    }

    /** 标记该面板是否处于降级模式（当前提权无法写节点，仅系统满亮度+背屏常亮）。 */
    public static void setDegraded(Context ctx, String key, boolean degraded) {
        prefs(ctx).edit().putBoolean(key + "_degraded", degraded).apply();
    }

    public static boolean isDegraded(Context ctx, String key) {
        return prefs(ctx).getBoolean(key + "_degraded", false);
    }
}
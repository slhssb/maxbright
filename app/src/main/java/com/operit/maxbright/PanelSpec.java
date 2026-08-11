package com.operit.maxbright;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个屏幕面板的规格定义（运行时自动探测版本）。
 *
 * 自动扫描 /sys/class/backlight/ 下的所有背光设备：
 * - 主屏：当前点亮（brightness > 0）的那个；全为 0 时取第一个
 * - 副屏：除主屏外的第二个设备（若存在）
 * - 老设备回退：/sys/class/leds/lcd-backlight/
 *
 * 这样可兼容绝大多数小米/红米及其他 Root 机型，不再写死路径与最大值。
 */
public final class PanelSpec {

    /** 主屏规格（检测后可用）。 */
    public static volatile PanelSpec MAIN;
    /** 副屏规格（仅双屏设备且检测到第二块背光时可用，否则为 null）。 */
    public static volatile PanelSpec SUB;

    public final String key;
    public final String node;
    public final int maxValue;
    public final String brightnessSetting;
    public final String modeSetting;
    public final String displayName;
    public final String deviceName;

    private PanelSpec(String key, String deviceName, String node, int maxValue,
                      String brightnessSetting, String modeSetting, String displayName) {
        this.key = key;
        this.node = node;
        this.maxValue = maxValue;
        this.brightnessSetting = brightnessSetting;
        this.modeSetting = modeSetting;
        this.displayName = displayName;
        this.deviceName = deviceName;
    }

    /** 确保已探测；未探测则执行探测。返回主屏是否探测成功。 */
    public static synchronized boolean ensureDetected() {
        if (MAIN != null) return true;
        return detect();
    }

    /** 执行探测。返回主屏是否探测成功。 */
    public static synchronized boolean detect() {
        List<PanelSpec> found = new ArrayList<>();

        // 1. 标准 backlight 子系统：一次 su 调用枚举所有设备
        PrivilegedShell.Result r = PrivilegedShell.run(
                "for d in /sys/class/backlight/*/; do " +
                        "n=$(basename \"$d\"); " +
                        "mx=$(cat \"${d}max_brightness\" 2>/dev/null); " +
                        "b=$(cat \"${d}brightness\" 2>/dev/null); " +
                        "[ -z \"$mx\" ] && mx=0; [ -z \"$b\" ] && b=0; " +
                        "echo \"$n|$mx|$b\"; " +
                        "done", 8000);
        if (r.ok()) {
            String[] lines = r.stdout.trim().split("\n");
            for (String line : lines) {
                String[] parts = line.split("\\|");
                if (parts.length < 3) continue;
                try {
                    String name = parts[0].trim();
                    int max = Integer.parseInt(parts[1].trim());
                    int cur = Integer.parseInt(parts[2].trim());
                    if (max <= 0) continue;
                    found.add(new PanelSpec(
                            "panel_" + name,
                            name,
                            "/sys/class/backlight/" + name + "/brightness",
                            max,
                            null, null, name));
                    // cur 暂记入 displayName 之外的用途：通过临时字段保存
                    found.get(found.size() - 1).currentHint = cur;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // 2. 回退：老式 lcd-backlight 节点
        if (found.isEmpty()) {
            PrivilegedShell.Result legacy = PrivilegedShell.run(
                    "mx=$(cat /sys/class/leds/lcd-backlight/max_brightness 2>/dev/null); " +
                            "b=$(cat /sys/class/leds/lcd-backlight/brightness 2>/dev/null); " +
                            "[ -z \"$mx\" ] && mx=0; [ -z \"$b\" ] && b=0; echo \"$mx|$b\"", 8000);
            if (legacy.ok()) {
                try {
                    String[] p = legacy.stdout.trim().split("\\|");
                    int max = Integer.parseInt(p[0].trim());
                    int cur = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
                    if (max > 0) {
                        PanelSpec spec = new PanelSpec(
                                "panel_main", "lcd-backlight",
                                "/sys/class/leds/lcd-backlight/brightness",
                                max, null, null, "lcd-backlight");
                        spec.currentHint = cur;
                        found.add(spec);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (found.isEmpty()) {
            MAIN = null;
            SUB = null;
            return false;
        }

        // 3. 主屏选择：优先当前点亮（brightness > 0）且最大值最大的设备
        PanelSpec main = found.get(0);
        for (PanelSpec s : found) {
            if (s.currentHint > 0 && s.maxValue >= main.maxValue) {
                main = s;
                break;
            }
        }

        // 4. 副屏选择：排除主屏后的第一个
        PanelSpec sub = null;
        for (PanelSpec s : found) {
            if (s != main) {
                sub = s;
                break;
            }
        }

        // 5. 绑定系统亮度设置键：主屏用标准键；副屏优先尝试小米双屏键
        MAIN = new PanelSpec("main", main.deviceName, main.node, main.maxValue,
                "screen_brightness", "screen_brightness_mode", "主屏");
        if (sub != null) {
            String brightKey = hasSetting("sub_display_screen_brightness")
                    ? "sub_display_screen_brightness" : "screen_brightness";
            String modeKey = hasSetting("sub_display_screen_brightness_mode")
                    ? "sub_display_screen_brightness_mode" : "screen_brightness_mode";
            SUB = new PanelSpec("sub", sub.deviceName, sub.node, sub.maxValue,
                    brightKey, modeKey, "副屏");
        } else {
            SUB = null;
        }
        return true;
    }

    private static boolean hasSetting(String key) {
        PrivilegedShell.Result r = PrivilegedShell.run("settings get system " + key, 5000);
        String v = r.stdout == null ? "" : r.stdout.trim();
        return r.ok() && !v.isEmpty() && !"null".equals(v);
    }

    /** 探测时的临时当前亮度提示值。 */
    private transient int currentHint;
}
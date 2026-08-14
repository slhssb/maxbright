package com.operit.maxbright;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 亮度最大化控制器：磁贴与主界面共用。
 *
 * 实测结论（小米双屏机）：
 * - 自动亮度下系统持续覆写节点，必须先切手动模式
 * - 手动模式下系统只在"事件"时写节点（唤醒/休眠），不会持续覆写
 * - 背屏的真正大敌是休眠超时（默认10s）：超时触发降亮度/关屏事件链重写节点
 * - 背屏 FLAG_OWN_CONTENT_ONLY：拒绝第三方窗口投放，保活窗口方案不可行
 *
 * 开启流程：
 *   记录现场 → 关自动亮度 + 亮度设置拉满
 *   →（背屏）超时设∞ + KEYCODE_WAKEUP 无害唤醒（重排休眠定时器，不触发整机睡眠）
 *   → 等爬升完成 → 直写硬件最大值 → 启动守护 burst 补写（兜底旧定时器残余）
 * 关闭流程：
 *   恢复亮度设置值 → 重开自动亮度 →（背屏）恢复原超时
 *
 * 关键教训：
 * - 绝不能用 KEYCODE_SLEEP(223)：它是系统级电源键，会触发整机睡眠锁屏
 * - 背屏拒绝第三方窗口投放（FLAG_OWN_CONTENT_ONLY），保活窗口方案无效
 * - KEYCODE_WAKEUP 是安全无害的，只点亮对应 display 并重排定时器
 */
public final class BrightnessController {

    /** 背屏休眠超时设置键（小米双屏机）。 */
    private static final String SUBSCREEN_TIMEOUT_KEY = "subscreen_display_time";
    /** ∞ 超时值：让背屏永不休眠，从根上消除休眠事件对节点的覆写。 */
    private static final int SUBSCREEN_TIMEOUT_INFINITY = 2147483647;

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private BrightnessController() {
    }

    /** 判断该面板是否为背屏（副屏）：第二块背光设备。 */
    private static boolean isSubPanel(PanelSpec panel) {
        return "sub".equals(panel.key);
    }

    public static void enable(final Context ctx, final PanelSpec panel, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            if (!PrivilegedShell.isPrivilegedAvailable()) {
                post(cb, false, "无可用提权：请授予 Root 权限或在 Shizuku 中授权本应用");
                return;
            }

            boolean sub = isSubPanel(panel);
            boolean fullCapable = PrivilegeManager.canWriteNodes(app);

            // 1. 记录现场（亮度值、亮度模式、背屏超时），用于关闭时恢复
            int brightness = BrightnessConfig.getSetting(panel.brightnessSetting, 0);
            int mode = BrightnessConfig.getPanelMode(panel);
            int timeout = sub
                    ? BrightnessConfig.getSetting(SUBSCREEN_TIMEOUT_KEY, 10000)
                    : -1;
            BrightnessConfig.saveSnapshot(app, panel.key, brightness, mode, timeout);

            // 2. 关闭自动亮度（切手动模式），否则系统会持续覆写硬件节点
            BrightnessConfig.setPanelMode(panel, 0);
            // 手动亮度档拉到最大，避免系统应用旧的手动映射值
            PrivilegedShell.run("settings put system " + panel.brightnessSetting + " 255");

            // 3. 背屏：把休眠超时设为∞，从根上消除休眠事件链（降亮度/关屏会重写节点）
            //    然后用 KEYCODE_WAKEUP 无害唤醒，让背屏管理器用新的∞超时重新排休眠定时器
            //    （KEYCODE_WAKEUP 只点亮对应 display，不触发整机睡眠/锁屏）
            if (sub) {
                PrivilegedShell.run("settings put system " + SUBSCREEN_TIMEOUT_KEY
                        + " " + SUBSCREEN_TIMEOUT_INFINITY);
                PrivilegedShell.run("input -d 1 keyevent KEYCODE_WAKEUP 2>/dev/null");
            }

            // 4. 等待系统亮度服务应用上述设置的爬升（ramp 约 1.5 秒）
            try {
                Thread.sleep(sub ? 2000 : 1200);
            } catch (InterruptedException ignored) {
            }

            if (!fullCapable) {
                // ===== 降级模式：当前提权（如 adb Shizuku shell）无法写背光节点 =====
                // 仅保留 settings 满亮度 + 背屏常亮，诚实告知用户无法达到硬件最大值。
                // 节点读取对 shell 是允许的，用于状态展示；不做直写。
                BrightnessConfig.setDegraded(app, panel.key, true);
                BrightnessConfig.setActive(app, panel.key, true);
                startKeepService(app); // 守护服务守卫 settings 不被复位（降级面板只守设置不写节点）
                post(cb, true, panel.displayName + "已开启降级模式：系统满亮度"
                        + (sub ? " + 背屏常亮" : "")
                        + "（当前提权无法写节点，需 Root 或 root 启动的 Shizuku 才能达到硬件最大值）");
                return;
            }

            // 5. 完整模式：直写硬件节点到最大值（内含回读重试）
            boolean ok = BrightnessConfig.writeNodeMax(panel.node, panel.maxValue);
            if (!ok) {
                // 探测可写但实际写失败：退回降级模式而不是直接报错
                BrightnessConfig.setDegraded(app, panel.key, true);
                BrightnessConfig.setActive(app, panel.key, true);
                startKeepService(app);
                post(cb, true, panel.displayName + "写入节点失败，已切换降级模式：系统满亮度"
                        + (sub ? " + 背屏常亮" : ""));
                return;
            }

            // 6. 标记激活并启动守护服务（burst 补写，兜底旧定时器残余的降亮度事件）
            BrightnessConfig.setDegraded(app, panel.key, false);
            BrightnessConfig.setActive(app, panel.key, true);
            startKeepService(app);

            post(cb, true, panel.displayName + "已锁定硬件最大亮度 " + panel.maxValue
                    + (sub ? "（已禁止背屏休眠）" : ""));
        }, "maxbright-enable-" + panel.key).start();
    }

    public static void disable(final Context ctx, final PanelSpec panel, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            BrightnessConfig.setActive(app, panel.key, false);
            BrightnessConfig.setDegraded(app, panel.key, false);
            boolean sub = isSubPanel(panel);

            // 恢复之前的亮度值与背屏超时
            int[] snapshot = BrightnessConfig.loadSnapshot(app, panel.key);
            if (snapshot != null) {
                PrivilegedShell.run("settings put system " + panel.brightnessSetting + " " + snapshot[0]);
                if (sub && snapshot.length > 2 && snapshot[2] > 0) {
                    PrivilegedShell.run("settings put system " + SUBSCREEN_TIMEOUT_KEY
                            + " " + snapshot[2]);
                } else if (sub) {
                    PrivilegedShell.run("settings put system " + SUBSCREEN_TIMEOUT_KEY + " 10000");
                }
                BrightnessConfig.clearSnapshot(app, panel.key);
            } else {
                PrivilegedShell.run("settings put system " + panel.brightnessSetting + " 128");
                if (sub) {
                    PrivilegedShell.run("settings put system " + SUBSCREEN_TIMEOUT_KEY + " 10000");
                }
            }

            // 关闭磁贴时重新开启系统自动亮度
            BrightnessConfig.setPanelMode(panel, 1);

            // 让守护服务重新评估（无激活面板时会自动停止）
            startKeepService(app);

            post(cb, true, panel.displayName + "已恢复原亮度与自动亮度");
        }, "maxbright-disable-" + panel.key).start();
    }

    /** 写入失败时回滚现场。 */
    private static void rollback(Context app, PanelSpec panel,
                                 int brightness, int mode, int timeout, boolean sub) {
        PrivilegedShell.run("settings put system " + panel.brightnessSetting + " " + brightness);
        BrightnessConfig.setPanelMode(panel, mode);
        if (sub && timeout > 0) {
            PrivilegedShell.run("settings put system " + SUBSCREEN_TIMEOUT_KEY + " " + timeout);
        }
        BrightnessConfig.clearSnapshot(app, panel.key);
    }

    /** 同步切换，返回新状态是否为开启（用于磁贴）。 */
    public static void toggle(Context ctx, PanelSpec panel, Callback cb) {
        if (BrightnessConfig.isActive(ctx, panel.key)) {
            disable(ctx, panel, cb);
        } else {
            enable(ctx, panel, cb);
        }
    }

    /**
     * 面板探测失败时的兜底关闭：
     * 无法写节点，仅恢复快照中的亮度设置并重开自动亮度。
     */
    public static void disableWithFallback(final Context ctx, final String key) {
        new Thread(() -> {
            int[] snapshot = BrightnessConfig.loadSnapshot(ctx, key);
            if (snapshot != null) {
                PrivilegedShell.run("settings put system screen_brightness " + snapshot[0]);
                BrightnessConfig.clearSnapshot(ctx, key);
            }
            PrivilegedShell.run("settings put system screen_brightness_mode 1");
        }, "maxbright-fallback-" + key).start();
    }

    private static void startKeepService(Context app) {
        Intent intent = new Intent(app, BrightnessKeepService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Exception ignored) {
            // 极端情况下启动失败不影响一次性写入的效果
        }
    }

    private static void post(final Callback cb, final boolean success, final String message) {
        if (cb == null) return;
        cb.onResult(success, message);
    }
}

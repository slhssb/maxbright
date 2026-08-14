package com.operit.maxbright;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

/**
 * 前台守护服务：保证激活面板的硬件节点稳定在最大值。
 *
 * 实测结论（小米双屏机，参考 ZHITool 思路）：
 * - 手动亮度模式下，系统不会持续覆写节点，只在"唤醒/休眠事件"时写
 * - 控制器开启时已把背屏休眠超时设为∞（subscreen_display_time），
 *   自动休眠事件链被根除，节点可长期稳定
 * - 残余干扰只剩"唤醒事件"：系统点亮面板时会触发约 1.5s 的亮度爬升，
 *   爬升过程会覆写节点把最大值压下去
 *
 * 因此守护策略化繁为简：
 * - 面板熄灭（bl_power != 0）：完全不动，尊重熄屏
 * - 面板点亮且值 == 最大值：稳定，什么都不做（省电）
 * - 面板点亮但值被压低（唤醒爬升干扰）：进入 BURST，
 *   专用线程以 250ms 间隔高频回写最大值，持续压过爬升窗口（约 4s），
 *   连续 2 轮回读达标后退出 BURST
 * - 顺带守卫设置不被复位：手动模式被改回自动则纠正；背屏超时被复位则改回∞
 *
 * 无激活面板时自动停止。
 */
public class BrightnessKeepService extends Service {

    private static final String CHANNEL_ID = "max_bright_keep";
    private static final int NOTIF_ID = 1001;

    private static final long POLL_INTERVAL_MS = 1000L;       // 巡检间隔
    private static final long BURST_WRITE_INTERVAL_MS = 250L; // BURST 回写间隔
    private static final long BURST_DURATION_MS = 4000L;      // 单次 BURST 时长（>1.5s 爬升）
    private static final int STABLE_POLLS_TO_LOCK = 2;        // 连续达标轮数 → 退出 BURST
    private static final String SUBSCREEN_TIMEOUT_KEY = "subscreen_display_time";
    private static final int SUBSCREEN_TIMEOUT_INFINITY = 2147483647;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private final Map<String, PanelState> panelStates = new HashMap<>();
    private Thread burstThread;

    /** 单个面板的守护状态。 */
    private static class PanelState {
        PanelSpec spec;
        boolean degraded;    // 降级模式：不写节点，仅守卫 settings
        long burstUntil;      // > now 表示处于 BURST 中
        int stableCount;      // 连续达标轮数
        int lastBlPower = -1;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            new Thread(() -> {
                try {
                    evaluate();
                } catch (Exception ignored) {
                }
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }, "maxbright-poll").start();
        }
    };

    /** 一轮巡检：读取各面板状态 → 决策 → 纠正设置 / 触发 BURST。 */
    private void evaluate() {
        PanelSpec.ensureDetected();

        boolean mainActive = BrightnessConfig.isActive(this, "main");
        boolean subActive = BrightnessConfig.isActive(this, "sub");
        synchronized (panelStates) {
            if (!mainActive) panelStates.remove("main");
            if (!subActive) panelStates.remove("sub");
        }
        if (!mainActive && !subActive) {
            handler.post(this::stopSelf);
            return;
        }

        // 1. 一次 su 调用读取所有激活面板的 bl_power 与 brightness
        StringBuilder readScript = new StringBuilder();
        if (mainActive && PanelSpec.MAIN != null) appendRead(readScript, "main", PanelSpec.MAIN);
        if (subActive && PanelSpec.SUB != null) appendRead(readScript, "sub", PanelSpec.SUB);
        PrivilegedShell.Result rr = PrivilegedShell.run(readScript.toString());
        Map<String, int[]> readings = parseReadings(rr.stdout);

        // 2. 逐面板决策
        StringBuilder fixes = new StringBuilder();
        synchronized (panelStates) {
            if (mainActive && PanelSpec.MAIN != null) {
                stepPanel("main", PanelSpec.MAIN, readings.get("main"), fixes);
            }
            if (subActive && PanelSpec.SUB != null) {
                stepPanel("sub", PanelSpec.SUB, readings.get("sub"), fixes);
            }
        }
        if (fixes.length() > 0) {
            fixes.append("exit 0");
            PrivilegedShell.run(fixes.toString());
        }
    }

    /** 推进单个面板状态。fixes 收集需要的一次性纠正命令。 */
    private void stepPanel(String key, PanelSpec p, int[] reading, StringBuilder fixes) {
        PanelState st = panelStates.get(key);
        if (st == null) {
            st = new PanelState();
            st.spec = p;
            panelStates.put(key, st);
        }
        // 同步降级状态（面板级持久化标记）
        st.degraded = BrightnessConfig.isDegraded(this, key);
        if (reading == null) return;
        int blPower = reading[0];
        int brightness = reading[1];
        st.lastBlPower = blPower;

        // 面板熄灭：尊重熄屏，不动任何节点/设置（等待点亮后自动接管）
        if (blPower != 0) {
            st.burstUntil = 0;
            st.stableCount = 0;
            return;
        }

        // 面板点亮：守卫设置不被复位
        // a) 亮度模式必须是手动，否则系统会按光线传感器覆写。
        //    OS4.0 之后副屏模式键写入了 secure 表且系统读 secure，因此副屏同时守卫两个表。
        if ("sub".equals(key)) {
            fixes.append("m=$(settings get secure ").append(p.modeSetting).append(" 2>/dev/null); ")
                 .append("[ -z \"$m\" ] && m=$(settings get system ").append(p.modeSetting).append("); ")
                 .append("[ \"$m\" != \"0\" ] && settings put secure ").append(p.modeSetting).append(" 0; ")
                 .append("[ \"$m\" != \"0\" ] && settings put system ").append(p.modeSetting).append(" 0; ");
        } else {
            fixes.append("m=$(settings get system ").append(p.modeSetting).append("); ")
                 .append("[ \"$m\" != \"0\" ] && settings put system ").append(p.modeSetting).append(" 0; ");
        }
        // b) 背屏超时必须保持∞，防止休眠事件链复位亮度
        if ("sub".equals(key)) {
            fixes.append("t=$(settings get system ").append(SUBSCREEN_TIMEOUT_KEY).append("); ")
                 .append("[ \"$t\" != \"").append(SUBSCREEN_TIMEOUT_INFINITY).append("\" ] && ")
                 .append("settings put system ").append(SUBSCREEN_TIMEOUT_KEY)
                 .append(" ").append(SUBSCREEN_TIMEOUT_INFINITY).append("; ");
        }

        // 降级模式：无法写节点，跳过亮度锁定/burst（settings 已拉满，其余交给系统）
        if (st.degraded) {
            st.burstUntil = 0;
            st.stableCount = 0;
            return;
        }

        long now = System.currentTimeMillis();
        if (brightness >= p.maxValue - 1) {
            // 值达标：计数，连续稳定后退出 BURST
            st.stableCount++;
            if (st.stableCount >= STABLE_POLLS_TO_LOCK) {
                st.burstUntil = 0;
            }
        } else {
            // 值被压低（唤醒爬升或事件覆写）：进入/延长 BURST 强力压制
            st.stableCount = 0;
            st.burstUntil = Math.max(st.burstUntil, now) + BURST_DURATION_MS;
        }
    }

    /** BURST 写线程：对 burstUntil > now 的面板每 250ms 回写一次最大值。 */
    private void startBurstWorker() {
        if (burstThread != null) return;
        burstThread = new Thread(() -> {
            while (running) {
                long now = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder();
                synchronized (panelStates) {
                    for (PanelState st : panelStates.values()) {
                        if (st.spec != null && st.burstUntil > now && st.lastBlPower == 0) {
                            sb.append("echo ").append(st.spec.maxValue)
                              .append(" > ").append(st.spec.node).append("; ");
                        }
                    }
                }
                if (sb.length() > 0) {
                    sb.append("exit 0");
                    PrivilegedShell.run(sb.toString());
                }
                try {
                    Thread.sleep(BURST_WRITE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "maxbright-burst");
        burstThread.start();
    }

    /** 生成读取 bl_power 与 brightness 的脚本片段。 */
    private void appendRead(StringBuilder sb, String key, PanelSpec p) {
        String dir = p.node.substring(0, p.node.lastIndexOf('/'));
        sb.append("b=$(cat ").append(p.node).append(" 2>/dev/null); ")
          .append("pp=$(cat ").append(dir).append("/bl_power 2>/dev/null); ")
          .append("echo \"").append(key).append("|${pp:--}|${b:--}\"; ");
    }

    /** 解析 "key|bl_power|brightness" 行。返回 key -> [blPower, brightness]。 */
    private Map<String, int[]> parseReadings(String out) {
        Map<String, int[]> map = new HashMap<>();
        if (out == null) return map;
        for (String line : out.split("\n")) {
            String[] parts = line.trim().split("\\|");
            if (parts.length != 3) continue;
            int blPower = parseIntOr(parts[1], 0);   // 读不到 bl_power 时视为点亮
            int brightness = parseIntOr(parts[2], 0);
            map.put(parts[0], new int[]{blPower, brightness});
        }
        return map;
    }

    private int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            handler.post(pollRunnable);
            startBurstWorker();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(pollRunnable);
        if (burstThread != null) {
            burstThread.interrupt();
            burstThread = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "亮度保持", NotificationManager.IMPORTANCE_MIN);
                channel.setDescription("保持屏幕处于硬件最大亮度");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("硬件最大亮度已启用")
                .setContentText("唤醒爬升自动压制，熄屏时让位")
                .setSmallIcon(R.drawable.ic_brightness_main)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
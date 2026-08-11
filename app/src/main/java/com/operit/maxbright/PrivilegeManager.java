package com.operit.maxbright;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 提权后端管理器：在 Root 与 Shizuku 之间选择，并探测各自能力。
 *
 * 三种模式：
 * - AUTO（默认）：优先 Shizuku（可用且有权限时），否则回退 Root
 * - ROOT_ONLY：仅用 Root（su）
 * - SHIZUKU_ONLY：仅用 Shizuku
 *
 * 能力事实（决定 Shizuku 能做到什么）：
 * - 背光节点属主是 system，只有 root 或 system 能写。
 * - Shizuku 以 root 启动（uid 0）→ 可写节点，与 Root 等效。
 * - Shizuku 以 adb(shell, uid 2000) 启动 → 只能改 settings/input，写不了节点。
 * 因此 Shizuku 后端会探测 uid，uid==0 才算"可写节点"。
 */
public final class PrivilegeManager {

    public enum Backend {
        AUTO, ROOT_ONLY, SHIZUKU_ONLY
    }

    private static final String PREFS = "privilege_prefs";
    private static final String KEY_BACKEND = "backend";

    /** Shizuku 服务连接状态（由 MaxBrightApp 的 binder 监听器更新）。 */
    private static volatile boolean shizukuBound = false;
    /** Shizuku 服务运行所在的 uid（0=root，2000=shell，-1=未知/未连接）。 */
    private static volatile int shizukuUid = -1;
    /** Shizuku 权限是否已被授予。 */
    private static volatile boolean shizukuGranted = false;
    /** Root 可用性缓存（null=未探测）。 */
    private static volatile Boolean rootAvailableCache = null;
    /** 应用级 Context（由 MaxBrightApp.onCreate 注入），供无 Context 的后台线程使用。 */
    private static volatile Context appContext = null;

    private PrivilegeManager() {
    }

    /** 注入应用 Context（MaxBrightApp.onCreate 调用）。 */
    public static void attach(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    /** 获取应用 Context（可能为 null）。 */
    public static Context getAppContext() {
        return appContext;
    }

    // ---------- 用户偏好 ----------

    public static Backend getPreferredBackend(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String v = p.getString(KEY_BACKEND, Backend.AUTO.name());
        try {
            return Backend.valueOf(v);
        } catch (Exception e) {
            return Backend.AUTO;
        }
    }

    public static void setPreferredBackend(Context ctx, Backend backend) {
        prefs(ctx).edit().putString(KEY_BACKEND, backend.name()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- Shizuku 状态（由 MaxBrightApp 更新） ----------

    public static void onShizukuConnected(int uid) {
        shizukuBound = true;
        shizukuUid = uid;
    }

    public static void onShizukuDisconnected() {
        shizukuBound = false;
        shizukuUid = -1;
    }

    public static void onShizukuPermissionChanged(boolean granted) {
        shizukuGranted = granted;
    }

    public static boolean isShizukuBound() {
        return shizukuBound;
    }

    public static int getShizukuUid() {
        return shizukuUid;
    }

    public static boolean isShizukuGranted() {
        return shizukuGranted;
    }

    /** Shizuku 当前是否以 root 运行（可写背光节点）。 */
    public static boolean isShizukuRootCapable() {
        return shizukuBound && shizukuUid == 0;
    }

    // ---------- Root 探测 ----------

    public static boolean isRootAvailable() {
        Boolean cached = rootAvailableCache;
        if (cached != null) return cached;
        boolean ok = probeRoot();
        rootAvailableCache = ok;
        return ok;
    }

    public static void invalidateRootCache() {
        rootAvailableCache = null;
    }

    private static boolean probeRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            StringBuilder out = new StringBuilder();
            Thread t = readInto(p.getInputStream(), out);
            boolean done = p.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroy();
                return false;
            }
            t.join(300);
            return p.exitValue() == 0 && out.toString().contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    private static Thread readInto(java.io.InputStream in, StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---------- 解析当前应使用的后端 ----------

    /**
     * 返回实际可用的执行后端。
     *
     * 选路策略（AUTO）：
     * 1) Shizuku 已连接+已授权且 uid=0（完整能力）→ shizuku
     * 2) Root 可用（完整能力）→ root
     * 3) Shizuku 已连接+已授权但 uid≠0（仅 settings 能力，降级模式可用）→ shizuku
     * 4) 都没有 → null
     *
     * @return "shizuku"、"root" 或 null。
     */
    public static String resolveActiveBackend(Context ctx) {
        Backend pref = getPreferredBackend(ctx);
        boolean rootOk = isRootAvailable();
        boolean shizukuAvail = isShizukuBound() && isShizukuGranted();

        switch (pref) {
            case ROOT_ONLY:
                return rootOk ? "root" : null;
            case SHIZUKU_ONLY:
                return shizukuAvail ? "shizuku" : null;
            case AUTO:
            default:
                if (shizukuAvail && shizukuUid == 0) return "shizuku";
                if (rootOk) return "root";
                if (shizukuAvail) return "shizuku";
                return null;
        }
    }

    /** 当前后端能否直写背光节点（root 级能力），决定走硬件最大值还是降级模式。 */
    public static boolean canWriteNodes(Context ctx) {
        String backend = resolveActiveBackend(ctx);
        if (backend == null) return false;
        if ("root".equals(backend)) return true;
        return shizukuUid == 0;
    }

    /** 无 Context 版本的节点写入能力判断。 */
    public static boolean canWriteNodesCached() {
        Context ctx = appContext;
        if (ctx != null) return canWriteNodes(ctx);
        String backend = resolveBackendCached();
        if (backend == null) return false;
        if ("root".equals(backend)) return true;
        return shizukuUid == 0;
    }

    /**
     * 无 Context 版本的后端解析（供后台线程/守护服务使用）。
     * 使用 appContext；若未注入则按 AUTO 处理。
     * @return "shizuku"、"root" 或 null。
     */
    public static String resolveBackendCached() {
        Context ctx = appContext;
        if (ctx != null) {
            return resolveActiveBackend(ctx);
        }
        // 未注入 Context 时的兜底：AUTO 逻辑
        boolean rootOk = isRootAvailable();
        boolean shizukuAvail = isShizukuBound() && isShizukuGranted();
        if (shizukuAvail && shizukuUid == 0) return "shizuku";
        if (rootOk) return "root";
        if (shizukuAvail) return "shizuku";
        return null;
    }

    /** 人类可读的当前后端与能力描述（用于 UI）。 */
    public static String describeStatus(Context ctx) {
        boolean rootOk = isRootAvailable();
        StringBuilder sb = new StringBuilder();
        sb.append("Root: ").append(rootOk ? "可用" : "不可用").append('\n');
        if (shizukuBound) {
            sb.append("Shizuku: 已连接，uid=").append(shizukuUid);
            if (shizukuUid == 0) sb.append("（root，可写节点，完整功能）");
            else if (shizukuUid == 2000) sb.append("（shell，仅系统设置通道）");
            else sb.append("（权限受限）");
            sb.append('\n');
            sb.append("Shizuku 权限: ").append(shizukuGranted ? "已授予" : "未授予").append('\n');
        } else {
            sb.append("Shizuku: 未连接/未安装\n");
        }
        String active = resolveActiveBackend(ctx);
        sb.append("当前后端: ").append(active == null ? "无（不可用）" : active).append('\n');
        if (canWriteNodes(ctx)) {
            sb.append("能力: 完整（可直写硬件节点到最大值）");
        } else if (active != null) {
            sb.append("能力: 降级（仅系统满亮度 + 背屏常亮，无法达到硬件最大值）");
        } else {
            sb.append("能力: 无");
        }
        return sb.toString();
    }
}
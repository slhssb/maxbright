package com.operit.maxbright;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 统一特权命令执行层：根据 PrivilegeManager 解析的后端，
 * 自动路由到 Root（su）或 Shizuku（Shizuku.newProcess）。
 *
 * 调用方无需关心底层用哪个后端，直接 PrivilegedShell.run(command)。
 * 无可用后端时命令返回失败结果（不抛异常）。
 *
 * Shizuku 说明：
 * Shizuku.newProcess(String[], String[], String) 是 private static 方法（官方标记 deprecated
 * 但 API 13 仍可用且稳定），它调用 IShizukuService.newProcess，返回的
 * ShizukuRemoteProcess 直接继承 java.lang.Process，可以像普通进程一样读流/等待/销毁。
 * 注意：Shizuku.transactRemote 是 binder 代理透传机制（配合 ShizukuBinderWrapper 转发
 * 对系统服务的调用），不是用来执行命令的。
 */
public final class PrivilegedShell {

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    private PrivilegedShell() {
    }

    public static Result run(String command) {
        return run(command, 8000L);
    }

    /**
     * 执行特权命令。后端由 PrivilegeManager.resolveBackendCached 决定：
     * - "shizuku"：Shizuku.newProcess（继承 Shizuku 的 uid）
     * - "root"：su -c
     * - null：无可用后端，返回失败
     */
    public static Result run(String command, long timeoutMs) {
        String backend = PrivilegeManager.resolveBackendCached();
        if (backend == null) {
            return new Result(-1, "", "no_privilege_backend");
        }
        if ("shizuku".equals(backend)) {
            return runViaShizuku(command, timeoutMs);
        }
        return runViaRoot(command, timeoutMs);
    }

    /** 通过 Root su 执行。 */
    private static Result runViaRoot(String command, long timeoutMs) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return waitAndCollect(process, timeoutMs);
        } catch (Exception e) {
            return new Result(-1, "", e.getMessage() == null ? "error" : e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** 通过 Shizuku.newProcess 执行（继承 Shizuku 服务的 uid）。 */
    private static Result runViaShizuku(String command, long timeoutMs) {
        // 仅 AUTO 模式下 Shizuku 失败才回退 Root；
        // SHIZUKU_ONLY 模式失败时明确报错，尊重用户选择。
        android.content.Context ctx = PrivilegeManager.getAppContext();
        boolean allowFallback = ctx == null
                || PrivilegeManager.getPreferredBackend(ctx) == PrivilegeManager.Backend.AUTO;

        // 启动时序：Shizuku binder 可能尚未收到，等待其就绪（避免 requireService 抛异常）
        if (!waitForShizukuBinder(3000)) {
            android.util.Log.w("MaxBright", "runViaShizuku: binder not received in 3s");
            if (allowFallback) return runViaRoot(command, timeoutMs);
            return new Result(-1, "", "shizuku_binder_not_received");
        }

        try {
            Process process = newShizukuProcess(command);
            if (process == null) {
                android.util.Log.w("MaxBright", "runViaShizuku: newProcess returned null");
                if (allowFallback) return runViaRoot(command, timeoutMs);
                return new Result(-1, "", "shizuku_newprocess_null");
            }
            try {
                return waitAndCollect(process, timeoutMs);
            } finally {
                process.destroy();
            }
        } catch (Throwable e) {
            android.util.Log.e("MaxBright", "runViaShizuku error", e);
            if (allowFallback) return runViaRoot(command, timeoutMs);
            return new Result(-1, "", "shizuku_error: " + e.getMessage());
        }
    }

    /** 等待 Shizuku binder 就绪（ShizukuProvider 的 sticky 回调可能晚于首次命令）。 */
    private static boolean waitForShizukuBinder(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                if (rikka.shizuku.Shizuku.pingBinder()) return true;
            } catch (Throwable ignored) {
            }
            if (System.currentTimeMillis() >= deadline) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        try {
            return rikka.shizuku.Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反射缓存 Shizuku.newProcess 方法句柄。 */
    private static volatile Method newProcessMethod;

    /**
     * 通过反射调用 Shizuku.newProcess 创建远程进程。
     * 返回的 ShizukuRemoteProcess 继承 java.lang.Process。
     */
    private static Process newShizukuProcess(String command) {
        try {
            Method m = newProcessMethod;
            if (m == null) {
                m = rikka.shizuku.Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                m.setAccessible(true);
                newProcessMethod = m;
            }
            Object obj = m.invoke(null,
                    new String[]{"sh", "-c", command}, null, null);
            return (Process) obj;
        } catch (Throwable t) {
            android.util.Log.e("MaxBright", "newShizukuProcess failed: " + t, t);
            return null;
        }
    }

    /** 等待进程完成并收集输出。
     *
     * 注意：不能用 JDK 默认的 process.waitFor(timeout, unit)——它轮询 exitValue()
     * 并只捕获 IllegalThreadStateException；而 ShizukuRemoteProcess.exitValue() 在
     * 进程未退出时抛 IllegalArgumentException，会导致等待直接崩溃。
     * 这里改为：独立线程跑阻塞式 waitFor()（Shizuku 已重写为 binder 等待），join 超时。
     */
    private static Result waitAndCollect(Process process, long timeoutMs) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tOut = readAsync(process.getInputStream(), out);
        Thread tErr = readAsync(process.getErrorStream(), err);

        final int[] exitCode = {-2};
        Thread tWait = new Thread(() -> {
            try {
                exitCode[0] = process.waitFor();
            } catch (InterruptedException e) {
                exitCode[0] = -1;
            }
        }, "maxbright-wait");
        tWait.setDaemon(true);
        tWait.start();
        try {
            tWait.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }

        if (exitCode[0] == -2) {
            // 超时：销毁进程，再给等待线程一点时间收尾
            process.destroy();
            try {
                tWait.join(1000);
            } catch (InterruptedException ignored) {
            }
            joinQuietly(tOut, 300);
            joinQuietly(tErr, 300);
            if (exitCode[0] == -2) {
                return new Result(-1, out.toString(), "timeout");
            }
        }

        joinQuietly(tOut, 500);
        joinQuietly(tErr, 500);
        return new Result(exitCode[0], out.toString(), err.toString());
    }

    private static void joinQuietly(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 检测是否有可用特权后端（触发 Root 授权弹窗/Shizuku 授权）。 */
    public static boolean isPrivilegedAvailable() {
        String backend = PrivilegeManager.resolveBackendCached();
        if (backend == null) return false;
        Result r = run("id");
        return r.ok() && r.stdout.contains("uid=");
    }

    private static Thread readAsync(InputStream in, StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
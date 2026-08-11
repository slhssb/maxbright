package com.operit.maxbright;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 管理界面：显示探测到的背光面板与当前节点值，提供主/副屏开关。
 * 面板规格运行时自动探测，兼容不同机型。纯代码布局，无外部资源依赖。
 */
public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Switch mainSwitch;
    private Switch subSwitch;
    private TextView statusText;
    private TextView privilegeStatusText;
    private LinearLayout switchArea;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView root = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        layout.setPadding(pad, pad, pad, pad);
        root.addView(layout, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("硬件最大亮度");
        title.setTextSize(26);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("通过提权（Root 或 Shizuku）直写背光节点，将屏幕亮度推到硬件理论最大值。"
                + "\n\n应用会自动检测本机的背光设备及其最大档位，兼容绝大多数小米/红米机型。"
                + "\n\n开启时自动关闭系统自动亮度（否则亮度锁不住），关闭时恢复原亮度值并重新打开自动亮度。"
                + "\n\n能力说明："
                + "\n· Root / root 启动的 Shizuku → 完整模式：锁定硬件最大值"
                + "\n· adb 启动的 Shizuku（shell）→ 降级模式：系统满亮度 + 背屏常亮（写不了硬件节点）\n");
        desc.setTextSize(14);
        layout.addView(desc);

        // ===== 权限后端选择区 =====
        buildPrivilegeSection(layout);

        switchArea = new LinearLayout(this);
        switchArea.setOrientation(LinearLayout.VERTICAL);
        layout.addView(switchArea);

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setText("正在检测背光面板…");
        layout.addView(statusText, lp(20));

        Button refresh = new Button(this);
        refresh.setText("重新检测并刷新状态");
        refresh.setOnClickListener(v -> {
            switchArea.removeAllViews();
            statusText.setText("正在检测背光面板…");
            detectAndBuild();
        });
        layout.addView(refresh);

        TextView tip = new TextView(this);
        tip.setText("提示：把「主屏最大亮度」「副屏最大亮度」磁贴添加到控制中心即可快捷开关。"
                + "若本机没有副屏背光，副屏磁贴会提示不可用。"
                + "长时间使用最大亮度会加速屏幕老化并显著增加功耗，请按需开启。");
        tip.setTextSize(12);
        tip.setPadding(0, dp(16), 0, 0);
        layout.addView(tip);

        detectAndBuild();
    }

    /** 构建"提权后端"选择区：自动 / 仅 Root / 仅 Shizuku。 */
    private void buildPrivilegeSection(LinearLayout layout) {
        TextView privilegeTitle = new TextView(this);
        privilegeTitle.setText("提权方式");
        privilegeTitle.setTextSize(16);
        privilegeTitle.setTypeface(privilegeTitle.getTypeface(), android.graphics.Typeface.BOLD);
        layout.addView(privilegeTitle, lp(6));

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);

        RadioButton rbAuto = new RadioButton(this);
        rbAuto.setText("自动（优先 Shizuku，回退 Root）");
        RadioButton rbRoot = new RadioButton(this);
        rbRoot.setText("仅 Root（KernelSU / Magisk）");
        RadioButton rbShizuku = new RadioButton(this);
        rbShizuku.setText("仅 Shizuku");
        rbAuto.setId(RadioButton.generateViewId());
        rbRoot.setId(RadioButton.generateViewId());
        rbShizuku.setId(RadioButton.generateViewId());

        group.addView(rbAuto);
        group.addView(rbRoot);
        group.addView(rbShizuku);

        // 回显当前偏好
        PrivilegeManager.Backend cur = PrivilegeManager.getPreferredBackend(this);
        if (cur == PrivilegeManager.Backend.ROOT_ONLY) group.check(rbRoot.getId());
        else if (cur == PrivilegeManager.Backend.SHIZUKU_ONLY) group.check(rbShizuku.getId());
        else group.check(rbAuto.getId());

        group.setOnCheckedChangeListener((g, checkedId) -> {
            PrivilegeManager.Backend backend = PrivilegeManager.Backend.AUTO;
            if (checkedId == rbRoot.getId()) backend = PrivilegeManager.Backend.ROOT_ONLY;
            else if (checkedId == rbShizuku.getId()) backend = PrivilegeManager.Backend.SHIZUKU_ONLY;
            PrivilegeManager.setPreferredBackend(this, backend);
            PrivilegeManager.invalidateRootCache();
            refreshPrivilegeStatus();
        });
        layout.addView(group, lp(8));

        privilegeStatusText = new TextView(this);
        privilegeStatusText.setTextSize(13);
        privilegeStatusText.setText("权限状态检测中…");
        layout.addView(privilegeStatusText, lp(12));

        Button refreshPriv = new Button(this);
        refreshPriv.setText("刷新权限状态");
        refreshPriv.setOnClickListener(v -> refreshPrivilegeStatus());
        layout.addView(refreshPriv, lp(8));

        Button reqShizuku = new Button(this);
        reqShizuku.setText("请求 Shizuku 授权");
        reqShizuku.setOnClickListener(v -> requestShizukuPermission());
        layout.addView(reqShizuku, lp(16));
    }

    /** 请求 Shizuku 授权（弹出 Shizuku 授权对话框）。 */
    private void requestShizukuPermission() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku 服务未运行", Toast.LENGTH_SHORT).show();
                return;
            }
            if (rikka.shizuku.Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "已有 Shizuku 权限", Toast.LENGTH_SHORT).show();
                PrivilegeManager.onShizukuPermissionChanged(true);
                refreshPrivilegeStatus();
                return;
            }
            if (rikka.shizuku.Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this, "Shizuku 权限被拒绝，请在 Shizuku 应用中手动授予",
                        Toast.LENGTH_LONG).show();
                return;
            }
            rikka.shizuku.Shizuku.requestPermission(1001);
            Toast.makeText(this, "请在弹出的 Shizuku 对话框中允许", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "请求失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 异步刷新权限状态显示。 */
    private void refreshPrivilegeStatus() {
        new Thread(() -> {
            final String text = PrivilegeManager.describeStatus(this);
            handler.post(() -> privilegeStatusText.setText(text));
        }, "maxbright-priv").start();
    }

    /** 异步探测面板并构建开关 UI。 */
    private void detectAndBuild() {
        new Thread(() -> {
            boolean ok = PanelSpec.ensureDetected();
            handler.post(() -> {
                switchArea.removeAllViews();
                if (!ok || PanelSpec.MAIN == null) {
                    statusText.setText("❌ 未检测到背光设备。\n请确认已授予 Root 权限或 Shizuku 权限，或本机使用了不受支持的调光驱动。");
                    return;
                }
                buildSwitches();
                refreshStatus();
            });
        }, "maxbright-detect").start();
    }

    private void buildSwitches() {
        PanelSpec main = PanelSpec.MAIN;
        mainSwitch = new Switch(this);
        mainSwitch.setText("主屏最大亮度（" + main.deviceName + " = " + main.maxValue + "）");
        mainSwitch.setTextSize(17);
        mainSwitch.setChecked(BrightnessConfig.isActive(this, main.key));
        mainSwitch.setOnCheckedChangeListener((v, checked) -> togglePanel(main, checked, v));
        switchArea.addView(mainSwitch, lp(4));
        TextView mainHint = new TextView(this);
        mainHint.setTextSize(12);
        mainHint.setTextColor(0xFF888888);
        refreshModeHint(mainHint, main.key);
        switchArea.addView(mainHint, lp(12));

        PanelSpec sub = PanelSpec.SUB;
        if (sub != null) {
            subSwitch = new Switch(this);
            subSwitch.setText("副屏最大亮度（" + sub.deviceName + " = " + sub.maxValue + "）");
            subSwitch.setTextSize(17);
            subSwitch.setChecked(BrightnessConfig.isActive(this, sub.key));
            subSwitch.setOnCheckedChangeListener((v, checked) -> togglePanel(sub, checked, v));
            switchArea.addView(subSwitch, lp(4));
            TextView subHint = new TextView(this);
            subHint.setTextSize(12);
            subHint.setTextColor(0xFF888888);
            refreshModeHint(subHint, sub.key);
            switchArea.addView(subHint, lp(12));
        } else {
            subSwitch = null;
            TextView noSub = new TextView(this);
            noSub.setText("未检测到副屏背光（本机可能没有副屏）。");
            noSub.setTextSize(13);
            switchArea.addView(noSub, lp(12));
        }
    }

    /** 开关下方的模式提示：完整模式 / 降级模式。 */
    private void refreshModeHint(TextView hint, String key) {
        boolean active = BrightnessConfig.isActive(this, key);
        boolean degraded = BrightnessConfig.isDegraded(this, key);
        boolean canWrite = PrivilegeManager.canWriteNodes(this);
        if (active && degraded) {
            hint.setText("⚠ 降级模式运行中：系统满亮度" + ("sub".equals(key) ? " + 常亮" : "")
                    + "（非硬件最大值）");
        } else if (canWrite) {
            hint.setText("完整模式：可锁定硬件最大值");
        } else {
            hint.setText("当前提权无法写节点，开启后将运行降级模式（系统满亮度"
                    + ("sub".equals(key) ? " + 常亮" : "") + "）");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mainSwitch != null && PanelSpec.MAIN != null) {
            mainSwitch.setChecked(BrightnessConfig.isActive(this, PanelSpec.MAIN.key));
        }
        if (subSwitch != null && PanelSpec.SUB != null) {
            subSwitch.setChecked(BrightnessConfig.isActive(this, PanelSpec.SUB.key));
        }
        refreshPrivilegeStatus();
    }

    private LinearLayout.LayoutParams lp(int bottomMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(bottomMarginDp);
        return p;
    }

    private void togglePanel(final PanelSpec panel, final boolean checked, final CompoundButton switchView) {
        switchView.setEnabled(false);
        BrightnessController.Callback cb = (success, message) -> handler.post(() -> {
            boolean active = BrightnessConfig.isActive(this, panel.key);
            switchView.setChecked(active);
            switchView.setEnabled(true);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        if (checked) {
            BrightnessController.enable(this, panel, cb);
        } else {
            BrightnessController.disable(this, panel, cb);
        }
    }

    private void refreshStatus() {
        new Thread(() -> {
            PanelSpec main = PanelSpec.MAIN;
            PanelSpec sub = PanelSpec.SUB;
            StringBuilder sb = new StringBuilder("检测结果：\n");
            if (main != null) {
                int cur = BrightnessConfig.readNode(main.node);
                sb.append("主屏 [").append(main.deviceName).append("] ")
                  .append(cur < 0 ? "?" : String.valueOf(cur))
                  .append(" / ").append(main.maxValue).append("\n");
            }
            if (sub != null) {
                int cur = BrightnessConfig.readNode(sub.node);
                sb.append("副屏 [").append(sub.deviceName).append("] ")
                  .append(cur < 0 ? "?" : String.valueOf(cur))
                  .append(" / ").append(sub.maxValue).append("\n");
            }
            final String text = sb.toString();
            handler.post(() -> statusText.setText(text));
        }, "maxbright-status").start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
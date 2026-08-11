package com.operit.maxbright;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * 磁贴基类：点击切换指定面板的硬件最大亮度。
 * 面板规格（节点/最大值）运行时自动探测，兼容不同机型。
 * 要求 Android 7.0+（TileService 最低 API 24）。
 */
public abstract class BaseTileService extends TileService {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 该磁贴针对的面板键："main" 或 "sub"。 */
    protected abstract String panelKey();

    /** 探测后获取对应面板规格，可能为 null（如无副屏的设备上点副屏磁贴）。 */
    private PanelSpec currentPanel() {
        return "sub".equals(panelKey()) ? PanelSpec.SUB : PanelSpec.MAIN;
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        boolean active = BrightnessConfig.isActive(this, panelKey());
        updateTile(active, currentPanel());
    }

    @Override
    public void onClick() {
        super.onClick();
        final String key = panelKey();
        boolean active = BrightnessConfig.isActive(this, key);
        if (active) {
            // 关闭路径：无需探测也能恢复（用快照恢复设置）
            PanelSpec p = currentPanel();
            if (p == null) {
                // 探测不到面板时仅复位状态
                BrightnessConfig.setActive(this, key, false);
                BrightnessController.disableWithFallback(this, key);
                mainHandler.post(() -> updateTile(false, null));
                return;
            }
            BrightnessController.disable(this, p, (success, message) ->
                    mainHandler.post(() -> {
                        updateTile(false, p);
                        showToast(message);
                    }));
        } else {
            // 开启路径：先异步探测面板再执行
            updateTileLoading();
            new Thread(() -> {
                boolean detected = PanelSpec.ensureDetected();
                final PanelSpec p = currentPanel();
                mainHandler.post(() -> {
                    if (!detected || p == null) {
                        updateTile(false, null);
                        showToast("未检测到背光设备"
                                + ("sub".equals(key) ? "（本机可能没有副屏背光）" : ""));
                        return;
                    }
                    BrightnessController.enable(this, p, (success, message) ->
                            mainHandler.post(() -> {
                                updateTile(BrightnessConfig.isActive(this, key), p);
                                showToast(message);
                            }));
                });
            }, "maxbright-tile-" + key).start();
        }
    }

    private void updateTile(boolean active, PanelSpec p) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            boolean degraded = active && BrightnessConfig.isDegraded(this, panelKey());
            String subtitle;
            if (!active) {
                subtitle = "点击开启最大亮度";
            } else if (degraded) {
                subtitle = "降级·系统满亮度";
            } else {
                subtitle = p != null ? "硬件亮度 " + p.maxValue : "已开启";
            }
            tile.setSubtitle(subtitle);
        }
        tile.updateTile();
    }

    private void updateTileLoading() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_ACTIVE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.setSubtitle("检测中…");
        }
        tile.updateTile();
    }

    private void showToast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}
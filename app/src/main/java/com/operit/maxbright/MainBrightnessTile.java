package com.operit.maxbright;

/**
 * 控制中心磁贴：主屏硬件最大亮度。
 * 节点与最大值运行时自动探测，兼容不同机型。
 */
public class MainBrightnessTile extends BaseTileService {

    @Override
    protected String panelKey() {
        return "main";
    }
}
package com.operit.maxbright;

/**
 * 控制中心磁贴：副屏硬件最大亮度。
 * 仅在检测到第二块背光设备的机型上生效（如小米折叠屏/双屏机型）。
 */
public class SubBrightnessTile extends BaseTileService {

    @Override
    protected String panelKey() {
        return "sub";
    }
}
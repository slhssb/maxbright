# MaxBright（硬件最大亮度）

通过 **Root 或 Shizuku** 提权，直写背光节点，将屏幕亮度推到**硬件理论最大值**。
提供两个控制中心磁贴（主屏 / 副屏各一个），一键开启与恢复。

针对小米 17 系列等**背屏机型**深度优化，支持运行时自动探测背光设备，兼容绝大多数小米/红米机型。

## ✨ 功能特性

- 🔆 **硬件最大亮度**：直写 `/sys/class/backlight/*/brightness`，突破系统亮度条上限
- 🎛️ **双磁贴**：主屏、副屏独立开关，添加到控制中心即可快捷使用
- 🔍 **自动探测**：运行时扫描 `/sys/class/backlight/`，自动识别背光节点与最大档位
- 🔄 **自动亮度联动**：开启时自动关闭系统自动亮度，关闭时恢复原亮度与自动模式
- 📱 **副屏常亮**：锁定背屏休眠超时为 ∞，从根上消除休眠降亮度/关屏事件
- ⚡ **BURST 守护**：针对系统唤醒时约 1.5s 的亮度爬升窗口高频回写，保证锁定不被打回
- 🛡️ **双提权后端**：Root 与 Shizuku 可选（自动 / 仅 Root / 仅 Shizuku）
- 🪜 **降级模式**：无 root 的 adb Shizuku 也能获得"系统满亮度 + 背屏常亮"

## 📋 权限说明

| 提权方式 | 能力 |
|---|---|
| Root / root 启动的 Shizuku | **完整模式**：直写硬件节点到最大值 |
| adb 启动的 Shizuku（shell） | **降级模式**：系统满亮度 + 背屏常亮（写不了硬件节点） |

> 背光节点属主为 `system`，只有 root / system 权限可写。

## 🛠️ 构建

纯 Java 实现，依赖极少（仅 Shizuku API）。

- JDK 17
- Android SDK 35
- Gradle 9.1.0 / AGP 9.0.0

```bash
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

> ARM64 环境下可运行 `./setup_android_env.sh` 自动替换 aapt2。

## 📁 核心结构

```
app/src/main/java/com/operit/maxbright/
├── MaxBrightApp.java          # Application：Shizuku 监听初始化
├── PrivilegeManager.java      # 提权后端选择与能力探测
├── PrivilegedShell.java       # 统一特权命令执行层（Root / Shizuku）
├── BrightnessController.java  # 亮度锁定控制器（开启/关闭流程）
├── BrightnessKeepService.java # 守护服务（BURST 压制 + settings 守卫）
├── BrightnessConfig.java      # 节点读写 + 快照持久化
├── PanelSpec.java             # 运行时背光设备探测
├── BaseTileService.java       # 磁贴基类
├── MainBrightnessTile.java    # 主屏磁贴
├── SubBrightnessTile.java     # 副屏磁贴
└── MainActivity.java          # 管理界面
```

## ⚠️ 免责声明

长时间满亮度会加速 OLED 老化并显著增加功耗，请按需开启。
本应用直写硬件节点，由此产生的任何后果由使用者自行承担。

## 📄 License

MIT

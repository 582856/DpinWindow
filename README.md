# DpinWindow

小米平板 DP-in 窗口显示工具。应用名称为 **DP-in窗口显示**，包名为 `com.maple.dpinviewer`，预览界面和现代 LSPosed / Vector 模块集成在同一 APK 中，不修改系统文件。

## 功能

- 在 Android 中显示 DP 输入，支持音频、暂停恢复、系统小窗。
- 开启系统“应用独立音量”后，预览时可在音量面板调节“DP-in窗口显示”的独立音量；媒体音量控制整体大小。
- 双击视频区域进入或退出窗口全屏，小窗自动全屏；视频等比例居中显示，小窗尺寸和比例由系统拖拽管理。
- 普通界面的下方预览区域与顶部标题栏等宽，高度按 16:9 计算，保留圆角；高度不足时页面可纵向滚动。
- 插线后通过“进入‘屏幕扩展’”超级岛打开预览；保留原厂 Pro 和 5 秒重连逻辑。
- Miuix 按钮、信息卡片和文字组件，跟随系统浅色或深色主题。
- Release 构建启用 R8 优化、混淆和资源压缩，保留现代 Xposed 入口。

## 使用

支持 Xiaomi `yingtian`，需临时 root 和已启用的现代 LSPosed / Vector。模块作用域仅勾选 `com.milink.service`。

需在 LSPosed 中禁用旧模块，启用新包名并勾选米联作用域。

已适配米联 `18.2.2.1.2609281717`（180020201）和 `18.1.2.2.2608192125`（180010202）。Android 最低版本为 13。升级模块后，业务 Hook 在米联进程下次启动时生效；重载前应先拔出 DP 线，让旧进程正常关闭接收芯片。

## 编译

使用 JDK 17、Android SDK platform 37 / build-tools 36.0.0 和 Gradle 9.3.1。界面依赖 [Miuix 0.9.3](https://github.com/compose-miuix-ui/miuix)。

签名后 APK 输出到 `dist/DpinWindow-<version>.apk`；R8 映射表位于 `app/build/outputs/mapping/release/mapping.txt`。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。

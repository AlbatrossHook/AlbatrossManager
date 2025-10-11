# AlbatrossManager

Albatross Hook 插件生态的管理应用。该应用用于在已 Root 的 Android 设备上管理 Albatross Hook 插件的安装、启用和配置。应用依赖核心库与插件 APK 才能正常工作。

插件开发可参考示例项目：`https://github.com/AlbatrossHook/HideApp`。

## 概览

- 最低支持：Min SDK 24
- 目标版本：Target SDK 34
- 编译版本：Compile SDK 36
- 语言/工具链：Java 17、AndroidX、AGP 8.x
- 核心依赖：`lib/albatross.jar`（以 `compileOnly` 方式提供）
- 设备要求：需要root权限，对root方式没有要求，但和lsposed有冲突，必须关闭lsposed模块

## 演示

- 查看运行演示：[AlbatrossManager.mp4](AlbatrossManager.mp4)

<video src="AlbatrossManager.mp4" controls preload="metadata" style="max-width: 100%; height: auto;">
  当前环境不支持内嵌播放，请点击上方链接下载观看。
</video>

## 环境与前置条件

- 一台已 Root 的 Android 设备
- Android Studio（建议使用最新版稳定版）或 Gradle 8.x
- 本地 Java 17 环境
- 已构建或获取的插件 APK（符合 Albatross 插件规范）

## 构建步骤

项目使用 Gradle Version Catalog 与 AGP：

1) 使用 Android Studio 打开工程并等待同步；或使用命令行构建：

```bash
./gradlew :app:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/`。

### Release 构建与签名

`release` 构建类型从 `local.properties` 或环境变量读取签名参数：

支持的键（在 `local.properties` 或作为环境变量）：
- `RELEASE_STORE_FILE`（或 env `RELEASE_STORE_FILE`）—— 默认为仓库根目录下 `qing.jks`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

然后执行：

```bash
./gradlew :app:assembleRelease
```

## 安装与运行

1) 确保设备已 Root。
2) 安装所需的插件 APK（见下文“插件”章节）。
3) 安装管理器 APK：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

如为 release 版本，请安装已签名的 `app-release.apk`。

## 运行权限与查询

应用声明以下权限：
- `android.permission.QUERY_ALL_PACKAGES`：用于枚举设备上安装的应用，以便管理插件目标。
- `android.permission.REQUEST_INSTALL_PACKAGES`：用于辅助安装插件 APK。

并声明 `qing.albatross.PluginConfig` 的查询，以发现兼容的插件。

## Root 与核心

- Hook 相关能力依赖 Root；若设备未 Root，管理器界面可启动，但插件操作不会生效。
- 应用以 `compileOnly` 链接核心 `lib/albatross.jar`。请确保运行环境具备 Albatross 所需的核心/服务组件。

## 使用插件

1) 构建或获取声明了 `qing.albatross.PluginConfig` 动作的插件 APK。
2) 在设备上安装该插件。
3) 打开 AlbatrossManager，查看插件列表与详情。
4) 按需启用或配置插件功能。

插件开发参考实现：`https://github.com/AlbatrossHook/HideApp`。

## 常见问题

- 未发现插件：确认插件已安装且正确声明了MetaData。
- 功能不生效：确认设备已 Root，且核心服务已就绪。
- 签名报错：检查 `local.properties` 或环境变量中的签名配置是否完整。

## 许可协议

请参见仓库中的 `LICENSE` 文件。


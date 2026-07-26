# 抖音纯净播放

面向抖音 Android 客户端的 LSPosed Modern API 102 模块。播放视频时只保留视频画面，
暂停后恢复完整界面，并支持直接下载当前无水印视频。

## 主要功能

- 播放视频时隐藏视频画面以外的抖音界面和系统栏。
- 暂停视频时立即恢复完整界面，继续播放后再次进入纯净模式。
- 暂停后在右侧操作栏顶部显示半透明下载按钮，点击即可将当前无水印视频保存到系统
  `Download` 目录。
- 识别广告、图文、长文章及其他非视频条目，并自动切换到下一条内容。
- 功能安装后默认启用，无需在模块内进行额外配置。

## 兼容范围

| 项目 | 当前支持 |
| --- | --- |
| LSPosed API | Modern API 102 |
| 已验证抖音版本 | 39.7.0（versionCode 390701） |
| Android | 9（API 28）及以上 |
| 默认作用域 | `com.ss.android.ugc.aweme` |
| 模块版本 | 1.2.0 |

模块针对抖音 39.7.0 的运行时结构进行适配。抖音升级后，播放器类、数据模型或界面层级可能变化，
届时需要重新适配。

## 安装

1. 从 [`dist`](dist) 目录下载最新版
   [`douyin-immersive-lsp-api102-v1.2.0.apk`](dist/douyin-immersive-lsp-api102-v1.2.0.apk)。
2. 在手机上安装 APK。
3. 在 LSPosed 中启用“抖音纯净播放”模块。
4. 保持默认作用域“抖音”，然后强制停止并重新打开抖音。

最新版 APK 的 SHA-256：

```text
0C4896E54BEAE64454942F9896164DA3E6210FCCBB2EB0E5B81FD51C7742F5CD
```

## 使用方式

- 正常播放视频时，模块自动进入纯净播放模式。
- 点击视频暂停后，完整界面与右侧下载按钮会恢复显示。
- 点击“下载”按钮后，模块从当前视频播放地址中选择可用源并在后台保存文件；下载进度和结果通过
  Toast 提示。
- 下载完成的文件位于系统 `Download` 目录。

## 实现说明

- 播放状态通过 `TTVideoEngine`、`TTVideoEngineImplV2` 及其
  `VideoEngineListener` 回调进行跟踪。
- 视频地址依次从 `play_addr`、`play_addr_h264`、`play_addr_bytevc1` 中选择，
  不使用带水印的下载地址链。
- 界面处理在运行时定位当前 Activity 中的播放器视图及右侧操作栏，不依赖容易变化的资源 ID。
- Android 10 及以上通过 `MediaStore.Downloads` 保存文件；较低版本使用
  `DownloadManager`。

## 本地构建

构建环境：

- JDK 17
- Android SDK 35
- PowerShell 或其他可运行 Gradle Wrapper 的终端

执行完整检查和发布构建：

```powershell
.\gradlew.bat clean lintRelease test assembleRelease
```

Gradle 中间产物会写入系统临时目录下的 `douyin-immersive-gradle/app`，最终构建产物位于该目录的
`outputs/apk/release` 下。仓库中经过验证、可直接安装的版本保存在 [`dist`](dist) 目录。

## 项目结构

```text
app/src/main/java/com/codex/douyin/immersive/
├── MainActivity.java
└── hook/
    ├── DouyinModule.java
    ├── FeedContentTracker.java
    ├── ImmersiveUi.java
    ├── PlaybackState.java
    ├── PlayerHooks.java
    └── VideoDownloader.java
```

运行日志标签为 `DouyinImmersive`，可通过以下命令查看：

```shell
adb logcat -s DouyinImmersive
```

# 抖仙人

<img src="docs/douxianren-icon.png" alt="抖仙人图标" width="160">

面向抖音 Android 客户端的 LSPosed Modern API 102 纯净播放模块。播放视频时只保留视频画面，
暂停后恢复完整界面，并支持直接下载当前无水印视频和自定义内容跳过规则。

## 主要功能

- 播放视频时隐藏视频画面以外的抖音界面和系统栏。
- 沉浸播放时屏蔽隐藏按钮及其子控件的触摸响应；点击原按钮位置也只会暂停并恢复完整界面。
- 保留侧面长按的原生快进／快退手势及上下滑动切换视频，屏蔽中间长按菜单。
- 可选择在纯净播放时保留抖音原生弹幕，关闭后仍只显示视频画面。
- 暂停视频时立即恢复完整界面，继续播放后再次进入纯净模式。
- 暂停后在右侧操作栏顶部显示半透明下载按钮，点击即可将当前无水印视频保存到系统
  `Download` 目录。
- 可在模块设置页分别配置是否跳过广告、图文、直播和普通视频。
- 支持按关键词匹配普通视频的标题或介绍，命中后自动切换到下一条；关键词忽略英文大小写，
  可使用换行、逗号或分号分隔。
- 默认跳过广告、图文和直播，保留普通视频；关键词默认为空。

## 兼容范围

| 项目 | 当前支持 |
| --- | --- |
| LSPosed API | Modern API 102 |
| 已验证抖音版本 | 39.7.0（versionCode 390701） |
| 本次触摸回归验证 | 抖音 40.3.0（versionCode 400301）／Android 16 |
| Android | 9（API 28）及以上 |
| 默认作用域 | `com.ss.android.ugc.aweme` |
| 模块包名 | `com.zz.douyin` |
| 模块版本 | 1.4.3 |

模块针对抖音 39.7.0 的运行时结构进行适配。抖音升级后，播放器类、数据模型或界面层级可能变化，
届时需要重新适配。

## 安装

1. 从 [`dist`](dist) 目录下载最新版
   [`douxianren-lsp-api102-v1.4.3.apk`](dist/douxianren-lsp-api102-v1.4.3.apk)。
2. 在手机上安装 APK。
3. 在 LSPosed 中启用“抖仙人”模块。
4. 保持默认作用域“抖音”，然后强制停止并重新打开抖音。

从 1.4.1 起模块包名调整为 `com.zz.douyin`。它会作为新应用与旧包名版本并存，升级后请在
LSPosed 中重新启用新包名模块并确认作用域。

最新版 APK 的 SHA-256：

```text
E0130B4710DD9A177385051D7A7087E5A37F73E5574970C11DA4ABC31B2E72EA
```

## 使用方式

- 正常播放视频时，模块自动进入纯净播放模式；可在模块设置页开启或关闭“播放弹幕”。
- 点击视频暂停后，完整界面与右侧下载按钮会恢复显示。
- 沉浸播放时，在屏幕左右各四分之一范围长按可继续使用抖音原生快进／快退手势；
  隐藏的点赞、评论、收藏、分享、导航等按钮不会响应点击，暂停恢复界面后可正常使用。
- 打开“抖仙人”应用，可分别开关播放弹幕，以及广告、图文、直播和视频过滤；设置通过
  LSPosed Remote Preferences 同步到抖音进程。
- 在“视频关键词”中填写不想观看的词语并保存，普通视频的 `item_title`、`title` 或 `desc`
  包含任意关键词时会自动跳过。
- 点击“下载”按钮后，模块从当前视频播放地址中选择可用源并在后台保存文件；下载进度和结果通过
  Toast 提示。
- 下载完成的文件位于系统 `Download` 目录。

## 实现说明

- 播放状态通过 `TTVideoEngine`、`TTVideoEngineImplV2` 及其
  `VideoEngineListener` 回调进行跟踪。
- 视频地址依次从 `play_addr`、`play_addr_h264`、`play_addr_bytevc1` 中选择，
  不使用带水印的下载地址链。
- 广告按当前视频数据模型中的广告字段识别；无论广告是否带可播放视频，
  都会自动切换到下一条内容，同时不会受预加载广告容器影响而误跳正常视频。
- 直播使用抖音 39.7.0 `Aweme.isLive()` / `awemeType=101` 识别；关键词读取
  `item_title`、`title` 和 `desc`，仅应用于普通视频，不跨类型覆盖广告、图文或直播开关。
- 界面处理在运行时定位当前 Activity 中的播放器视图及右侧操作栏，不依赖容易变化的资源 ID。
- 滑动切换视频时使用绘制前隐藏守卫，并在新渲染层接管前持续保持纯净界面，避免控件闪烁。
- 触摸在分发到隐藏子控件前被过滤，只放行原生 `LongPressLayout` 及其祖先路径；已开始的
  视频手势继续接收移动、松手及取消事件，避免切换渲染层时卡在快进状态。
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

## 交流与支持

- Telegram 机器人：[联系机器人使用QW/DD仙人](https://t.me/DDxianren_bot)
- Telegram 交流群：[加入抖仙人交流群](https://t.me/+h0a6WUhfbj84ZTM9)

## 项目结构

```text
app/src/main/java/com/zz/douyin/
├── FilterPreferences.java
├── MainActivity.java
├── ModuleApplication.java
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

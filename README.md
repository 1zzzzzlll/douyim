# 抖仙人

<img src="docs/douxianren-icon.png" alt="抖仙人图标" width="160">

面向抖音 Android 客户端的 LSPosed Modern API 102 纯净播放模块。播放视频时只保留视频画面，
暂停后恢复完整界面，并支持下载当前无水印视频或 MP3 音频和自定义内容跳过规则。

## 主要功能

- 功能总开关：关闭后停止所有模块功能，并恢复抖音原有界面和操作；各子开关配置保留。
- 独立的“沉浸式播放”开关：关闭后保留原界面，内容过滤、双击拦截和暂停下载仍可使用。
- 可选“禁用屏幕双击”：拦截视频画面的原生双击事件，保留单击、滑动及侧边长按。
- 可选“双击打开评论区”：双击视频画面直接展开抖音原生评论区，同时拦掉点赞与爱心动画。
- 播放视频时隐藏视频画面以外的抖音界面和系统栏。
- 沉浸播放时屏蔽隐藏按钮及其子控件的触摸响应；点击原按钮位置也只会暂停并恢复完整界面。
- 保留侧面长按的原生快进／快退手势及上下滑动切换视频，屏蔽中间长按菜单。
- 可选择在纯净播放时保留抖音原生弹幕；40.8 起弹幕由 Compose、Functor、Canvas 多个渲染层
  组成，模块按整层保留，不再只放行其中一层。
- 暂停视频时立即恢复完整界面和视频页原始布局，避免介绍被底部菜单栏遮挡；继续播放后再次进入纯净模式。
- 暂停后在右侧操作栏顶部显示半透明下载按钮，点击可选择“视频（无水印 MP4）”或
  “音频（MP3）”，保存到系统 `Download` 目录。
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
| 暂停布局回归验证 | 抖音 40.4.0（versionCode 400401）／Android 16 |
| Android | 9（API 28）及以上 |
| 默认作用域 | `com.ss.android.ugc.aweme` |
| 模块包名 | `com.zz.douyin` |
| 本次适配设备 | 抖音 40.8.0（versionCode 400801）／Android 17 |
| 模块版本 | 1.6.0 |

模块基于抖音 39.7.0 的运行时结构适配，并针对 40.5.0 补充双击拦截、40.8.0 补充弹幕分层、
广告标记与图文渲染适配。抖音升级后，播放器类、数据模型或界面层级可能变化，届时需要重新适配。

## 安装

1. 从 [`dist`](dist) 目录下载最新版
   [`douxianren-lsp-api102-v1.6.0.apk`](dist/douxianren-lsp-api102-v1.6.0.apk)。
2. 在手机上安装 APK。
3. 在 LSPosed 中启用“抖仙人”模块。
4. 保持默认作用域“抖音”，然后强制停止并重新打开抖音。

从 1.4.1 起模块包名调整为 `com.zz.douyin`。它会作为新应用与旧包名版本并存，升级后请在
LSPosed 中重新启用新包名模块并确认作用域。

最新版 APK 的 SHA-256：

```text
4a5cc881b5f8daa44b2b9327e32280690a8d8f1f04ae8476f4ba0e49188a8e67
```

## 使用方式

- “功能总开关”和“沉浸式播放”默认开启，“禁用屏幕双击”和“双击打开评论区”默认关闭。
  关闭总开关时，内容过滤、自动切换、沉浸隐藏、手势拦截和下载入口全部停止生效；后台 MP3
  转换会取消。
- 开启“双击打开评论区”后，双击画面等价于点击右侧评论按钮；它优先于“禁用屏幕双击”，
  两个开关同时打开时按打开评论区处理。
- 单独关闭“沉浸式播放”只恢复抖音原有界面，不影响其他功能。开启时“播放弹幕”设置才影响画面。
- 正常播放视频时，模块自动进入纯净播放模式；可在模块设置页开启或关闭“播放弹幕”。
- 点击视频暂停后，完整界面与右侧下载按钮会恢复显示。
- 沉浸播放时，在屏幕左右各四分之一范围长按可继续使用抖音原生快进／快退手势；
  隐藏的点赞、评论、收藏、分享、导航等按钮不会响应点击，暂停恢复界面后可正常使用。
- 打开“抖仙人”应用，可分别开关播放弹幕，以及广告、图文、直播和视频过滤；设置通过
  LSPosed Remote Preferences 同步到抖音进程。
- 在“视频关键词”中填写不想观看的词语并保存，普通视频的 `item_title`、`title` 或 `desc`
  包含任意关键词时会自动跳过。
- 点击“下载”按钮选择视频或音频。音频从当前视频的完整音轨解码为 PCM 后编码为 MP3，
  保留当前视频实际声音；不直接下载背景音乐，也不通过修改扩展名伪装格式。
- 下载和转码在后台执行，开始、转换阶段及完成结果通过 Toast 提示。失败时清理未完成文件。
- 下载完成的文件位于系统 `Download` 目录。

## 实现说明

- 播放状态通过 `TTVideoEngine`、`TTVideoEngineImplV2` 及其
  `VideoEngineListener` 回调进行跟踪。
- 视频地址依次从 `play_addr`、`play_addr_h264`、`play_addr_bytevc1` 中选择，
  不使用带水印的下载地址链。
- 广告按当前视频数据模型中的广告字段识别：`isAd`、`awemeRawAd`、`adAwemeSource`、
  `gdAd`、`asyncAdInfo`、`linkAdData` 任一命中即视为广告。40.8 的电商、团购、直播预约和
  小程序广告只带后几种标记，旧版仅看前两种会漏跳。日志会输出实际命中的标记
  （`adMarkers=`），误判时可直接定位。
- 跳过动作优先把合成滑动手势直接投递给信息流自身的 `VerticalViewPager`，广告页的
  WebView／互动容器吞掉手势时也能翻页；投递失败才退回根 View。
- 直播使用抖音 39.7.0 `Aweme.isLive()` / `awemeType=101` 识别；关键词读取
  `item_title`、`title` 和 `desc`，仅应用于普通视频，不跨类型覆盖广告、图文或直播开关。
- 界面处理在运行时定位当前 Activity 中的播放器视图及右侧操作栏，不依赖容易变化的资源 ID。
- 滑动切换视频时使用绘制前隐藏守卫，并在新渲染层接管前持续保持纯净界面，避免控件闪烁。
- 触摸在分发到隐藏子控件前被过滤，只放行原生 `LongPressLayout` 及其祖先路径；已开始的
  视频手势继续接收移动、松手及取消事件，避免切换渲染层时卡在快进状态。
- 1.6.0 评论面板只按“是否真的有 comment 面板视图”判断，不再用播放器尺寸做启发式：
  图文的音频 Surface 很小，会把“面板打开”的判定永久拉成 true，导致退出评论区后无法
  恢复沉浸。点击后另有 1.2s 宽限期给面板做入场动画，最长保持 30s。
- 1.6.0 双击评论区改用真实触摸注入（部分按钮只响应触摸、不响应 performClick），并对
  “首个视频 / 操作栏尚未就绪”的情形重试搜索按钮；评论面板打开期间保留原生界面，面板
  关闭后立即恢复沉浸。展开的评论输入框不会因为重复点击被顶起来。
- 1.6.0 图文改为和大图渲染层一起保留：图集/图文由大图或 Skity 视图绘制，只保留视频
  Surface 会黑屏；现在无论数据模型是否已刷新，都会把居中的大图层并入保留集合，避免
  “视频→图文”切换时出现黑帧，也不再把“图文→视频”的界面多留一秒。
- 1.6.0 修复三类回归：① 40.8 的多渲染层弹幕只保留 Compose 一层导致弹幕变少，现按
  `DDanmakuComposeView`／`DanmakuFunctorView`／`DanmakuView` 及宿主
  `DanmakuAsyncRenderParent` 整层保留；② 图文内容只保留视频 Surface 会黑屏，现额外保留
  居中的大图 `ImageView`；③ 合成滑动后播放器位置基线未复位，会让“播放结束”判定连环触发、
  所有视频自动下滑，现每次模块翻页后重置进度基线，并且只有首帧位于片头才布防。
- 1.5.1 修复双击拦截：抖音 40.5.0 的信息流自行识别双击，模块通过
  `FeedComponentGroup.interceptDoubleClick` 消费双击，阻止后续点赞和爱心动画，
  保留原本的单击取消与双击状态。系统 `GestureDetector` 路径也独立拦截，覆盖父容器的手势分发。
- Android 10 及以上通过 `MediaStore.Downloads` 保存文件；较低版本使用
  `DownloadManager` 保存视频，音频写入公共 Download 目录（需要抖音已有存储权限）。
- 音频使用 Android `MediaExtractor` / `MediaCodec` 解码及 jump3r 1.0.5 的 Java LAME 核心编码，
  不依赖系统 MP3 编码器或额外安装转码软件。单声道目标 128 kbps，双声道目标 192 kbps；
  无音轨、异常源或不支持的音频格式会提示失败。临时视频和转码文件会在完成或失败后清理。
- jump3r 采用 LGPL 2.1 或后续版本，许可证和对应源代码链接随 APK 放在 `assets/licenses/` 中。

## 本地构建

构建环境：

- JDK 21（源码兼容 Java 17）
- Android SDK 37
- PowerShell 或其他可运行 Gradle Wrapper 的终端

执行完整检查和发布构建：

```powershell
.\gradlew.bat clean lintRelease test assembleRelease
```

Gradle 中间产物会写入系统临时目录下的 `douyin-immersive-gradle/app`，最终构建产物位于该目录的
`outputs/apk/release` 下。仓库中经过验证、可直接安装的版本保存在 [`dist`](dist) 目录。

## 发版流程

按顺序执行，漏掉第 5 步站点就不会更新。

1. **改版本号**：`app/build.gradle.kts` 中 `versionCode` +1、`versionName` 改为新版本；
   同时更新本文件「兼容范围」表、「安装」里的 APK 链接。
2. **构建与检查**：`.\gradlew.bat lintRelease test assembleRelease`，产物在
   `%TEMP%\douyin-immersive-gradle\app\outputs\apk\release\app-release.apk`。
3. **归档 APK**：复制为 `dist/douxianren-lsp-api102-v<版本>.apk`，用 `sha256sum` 计算校验值并
   回填到本文件「安装」一节。
4. **提交源码**：

   ```bash
   git add -A
   git commit -m "发布 <版本>"
   git push origin HEAD:master   # 工作区在 workbuddy 分支时这样推到 master
   ```

5. **发布 Release（两个仓库都要建，缺一不可）**：tag 统一为 `<versionCode>-<versionName>`
   （例如 `18-1.6.0`），标题写版本名，正文写更新日志，并挂上 APK 附件。

   ```bash
   # 自己的源码仓库
   gh release create 18-1.6.0 dist/douxianren-lsp-api102-v1.6.0.apk \
     -R 1zzzzzlll/douyim --title 1.6.0 --notes-file notes.md --target master

   # LSPosed 官方仓库（必须带 APK，机器人只认 release 附件）
   gh release create 18-1.6.0 dist/douxianren-lsp-api102-v1.6.0.apk \
     -R Xposed-Modules-Repo/com.zz.douyin --title 1.6.0 --notes-file notes.md
   ```

   官方仓库建完后，`modules.lsposed.org/module/com.zz.douyin` 会在 5–60 分钟内刷新；
   **只推 commit 不会更新站点**，官方仓库的 tag 也是机器人从 release 反推的。

6. **实机确认**：安装后强制停止并重开抖音；若行为没有变化，先重启手机——LSPosed 可能仍在加载
   旧 APK（可用 `adb shell pm path com.zz.douyin` 拉包比对 sha256 确认）。

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
    ├── AudioTranscoder.java
    ├── DouyinModule.java
    ├── DoubleTapGuard.java
    ├── FeedContentTracker.java
    ├── ImmersiveUi.java
    ├── Mp3Encoder.java
    ├── PlaybackState.java
    ├── PlayerHooks.java
    └── VideoDownloader.java
```

运行日志标签为 `DouyinImmersive`，可通过以下命令查看：

```shell
adb logcat -s DouyinImmersive
```

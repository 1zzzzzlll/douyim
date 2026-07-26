# 抖音纯净播放（LSPosed API 102）

面向抖音 `com.ss.android.ugc.aweme` 的现代 LSPosed 模块。

## 功能

- 视频播放时隐藏视频画面以外的 App UI 和系统栏。
- 用户暂停视频时恢复原界面，继续播放后再次隐藏。
- 用户暂停视频后，在右侧操作栏顶部显示半透明下载按钮；点击后将当前无水印视频保存到系统 `Download` 目录。

以上功能安装后默认启用，不需要额外配置。

## 兼容目标

- LSPosed Modern API：102
- 已分析和验证的抖音版本：39.7.0（versionCode 390701）
- Android：9 及以上
- 默认作用域：`com.ss.android.ugc.aweme`

播放器状态基于 39.7.0 内的 `com.ss.ttvideoengine.TTVideoEngine` /
`TTVideoEngineImplV2` 及其 `VideoEngineListener` 回调；界面处理通过运行时查找当前
Activity 中面积最大的 `SurfaceView` / `TextureView`，因此不依赖抖音易变化的资源 ID。

## 构建

```powershell
.\gradlew.bat clean assembleRelease
```

由于项目路径位于 OneDrive，Gradle 中间产物会写入系统临时目录
`douyin-immersive-gradle/app`，避免同步程序锁定打包缓存。

## 使用

1. 安装生成的 APK。
2. 在 LSPosed 中启用“抖音纯净播放”。
3. 保持默认作用域“抖音”。
4. 强制停止后重新打开抖音。

模块日志标签为 `DouyinImmersive`。

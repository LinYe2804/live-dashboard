# Android Agent（先同意，后上报）

这个 Android 应用默认是 Live Dashboard 的非 root 客户端；小米运动健康读取功能需要配套 LSPosed 桥接模块。
只有在用户明确同意后，才会上报所选设备的活动信息。

## 可上报内容

- 当前前台应用的包名和应用名
- 电量百分比与充电状态（可选）
- 网络类型（作为上下文元数据）
- 小米运动健康中的睡眠记录、实时睡眠状态、步数、距离、活动卡路里和最近心率（可选）

## 不会做的事情

- 普通活动上报不要求 root 权限
- 不做按键记录
- 不提取其他应用的消息或内容
- 不会在未启用的情况下偷偷自启动

## 需要的权限

- 使用情况访问权限（PACKAGE_USAGE_STATS），用于检测前台应用
- 前台服务权限，用于持续心跳上报
- 网络权限，用于调用 API 上报
- Android 13 及以上的通知权限

## 使用到的后端接口

- POST /api/consent
- POST /api/report
- POST /api/health-data

该应用兼容强制同意模式：

- 如果服务端设置 REQUIRE_EXPLICIT_CONSENT=true，会先上传同意状态，再上报活动数据。

## 本地构建

在 Android Studio 中打开以下目录：

- packages/android-agent

然后执行：

1. 同步 Gradle 项目。
2. 在 app 和 xiaomi-health-bridge 模块构建 release APK。
3. 输出路径为：
   - app/build/outputs/apk/release/app-release.apk
   - xiaomi-health-bridge/build/outputs/apk/release/xiaomi-health-bridge-release.apk

## 运行配置

1. 填写服务端 URL 和 token（支持 `http://` 与 `https://`）。
2. 授予使用情况访问权限。
3. 勾选并确认同意项。
4. 保存设置并启动追踪。

## 小米运动健康（Root / LSPosed）

1. 安装 `live-dashboard-android-agent.apk` 和 `live-dashboard-xiaomi-health-lsposed.apk`。
2. 在 LSPosed 中启用“实时看板 · 小米健康桥接”。
3. 模块作用域只勾选“小米运动健康”（`com.mi.health`）。
4. 强制停止并重新打开小米运动健康，或重启手机。
5. 在实时看板助手中开启“小米运动健康数据”并重新保存、启动。

桥接模块不联网，也不修改健康记录。它只在小米健康进程中将实时看板助手加入只读数据提供器白名单；实际上传仍由 Agent 按用户填写的服务器和 Token 完成。

当前桥接接口已针对小米运动健康 `3.58.0`（HyperOS 3）验证。小米更新应用后如果内部数据提供器路径发生变化，Agent 日志会显示读取不可用，需要同步更新桥接兼容层。

# 本次本地启动准备记录

日期：2026-10-12。开发分支：`dev/local-startup-20261012`，基于原始 main 提交 `5daf918`。

本次未修改 Android 业务源码。已在被 Git 忽略的 `local.properties` 中配置本机 Android SDK 路径，使用 JDK 17、Android SDK 35 与已有用户级 `DJI_API_KEY` 配置编译。该 Key 是否与 `com.skycommand.relay` 匹配仍需真机注册验证。

本机 JDK 遇到 Unix 域连接临时路径错误，构建时使用以下会话配置解决：

```powershell
# 先将 JAVA_HOME 指向本机 JDK 17，并将其 bin 加入 Path。
New-Item -ItemType Directory -Force .gradle | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Get-Location).Path)\.gradle"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

结果：Debug APK 构建成功；已通过无线 ADB 安装到小米 10 / Android 13 / arm64-v8a，并打开主界面。尚未完成 SDK 注册、电脑会话、遥控器及飞机连接验证。

手机通过无线 ADB 调试，USB 留给遥控器。Relay 界面填写 Sky Command 显示的 `ws://<电脑局域网IP>:8080/relay`，手动点击“保存并启动”并确认权限。本机小米系统禁止 ADB 模拟输入，故界面操作需在手机完成。

后续源码修改在开发分支提交；分享时推送该分支并发起 Pull Request，main 保留原始提交。`local.properties`、用户级 Gradle 密钥配置、APK 和构建缓存均不提交。本记录用于说明这些本地配置及验证结果。

提交标题采用 `fix: 中文摘要`、`feat: 中文摘要`、`docs: 中文摘要` 等格式；正文以中文写清问题或目的、具体改动、验证结果与未验证范围。每个提交围绕一个明确目的；已推送的提交不擅自改写。

## GitHub 交付范围

源码、必要资源、Gradle 脚本/wrapper、公共 `gradle.properties`、测试源码和脱敏文档随开发分支提交。用户级 DJI Key、`local.properties`、签名文件、真实 `.env`、缓存、日志及 APK/AAB 不提交；复制到 build 目录外的 APK/AAB 也已加入忽略规则。安装包如需分享，另行检查后作为产物交付，APK 可能包含构建时注入的 DJI Key。

本机工作区父目录的 START-HERE.md/AGENTS.md 不会随本仓库 push。完整交付流程见配套 Sky-Command 分支的 `docs/auto-discovery/GITHUB-DELIVERY.md`，本机并排克隆时可使用[交付说明链接](../../Sky-Command/docs/auto-discovery/GITHUB-DELIVERY.md)。本仓库现已包含独立网络探针与手机发现核心；正式自动连接尚未接线。

## 手机发现核心离线验证（2026-10-13）

新增纯 JVM `:relay-discovery`，不依赖 Android/DJI，不自行监听 UDP 或创建 WebSocket。配置 JDK 17 与上文 Java 临时目录后执行：

```powershell
./gradlew.bat :relay-discovery:test --offline --no-daemon --console=plain
./gradlew.bat :relay-discovery:dependencies --configuration runtimeClasspath --offline --no-daemon --console=plain
```

本机 34 项测试通过，运行依赖只有 Kotlin/JVM 与 Jackson；正式电脑编码器与手机样例比对一致。首次沙箱内 Gradle 因用户缓存锁拒绝访问失败，按权限机制在沙箱外使用已有缓存离线验证成功，未重新安装依赖。报告位于模块 build/reports/tests/test，JAR 与报告均被忽略，不提交。实际 Android 运行未接入核心，本轮未构建/安装新 APK，日常启动仍按手动地址步骤；后台/两设备/15 秒目标未验收。

## 可选连接选择 API 验证（2026-10-13）

gateway/session 允许无初始地址启动等待，以稳定选择对象暂停或替换电脑连接；合法 paired 在进入 ACTIVE 前经过本地同步门禁，结束事件标明旧代次。只切换会话，保留命令注册与业务对象；发现运行容器尚未接线，日常手机操作仍使用上述手动流程。

配置同一 JDK 17 与 Java 临时目录后执行：

```powershell
./gradlew.bat :relay-gateway:connection-session:test :relay-gateway:test :relay-gateway:transport-adapter:test :relay-gateway:command-dispatcher:test :relay-gateway:mission-transfer:test :relay-gateway:outbound-publisher:test :relay-discovery:test :relay-settings:endpoint-settings:test :app:compileDebugKotlin --offline --no-daemon --console=plain
```

最终构建成功：session 69、gateway 10、transport 14、command 13、mission 14、outbound 10、discovery 34、endpoint-settings 3，共 167 项报告，零失败/错误/跳过；本轮重跑前 6 模块的 130 项，后 2 模块 37 项 UP-TO-DATE。新增 21 项连接选择和 4 项 gateway 测试，App Kotlin 编译通过；没有重新安装依赖或构建/安装 APK。既有 SDK XML 版本及 Gradle 弃用警告仍存在，未作为测试失败。报告、缓存和 JAR 保持忽略；正式 UDP、后台/锁屏、多手机及 15 秒目标仍待验收。

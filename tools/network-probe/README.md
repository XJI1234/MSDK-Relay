# 校园网络前台测试 App

独立包名 `com.skycommand.networkprobe`，用于测量手机应用实际收到的 UDP 测试包，以及手机直连 Sky Command 的 `hello / paired` 握手。没有 DJI 依赖或应用 Key，也不修改 Relay 正式程序。正式自动发现协议、后台服务与接入边界仍待确定。

## 构建与安装

在 MSDK-Relay 根目录使用 JDK 17 / Android SDK 35，给本测试目录配置被忽略的 local.properties，或自行设置 ANDROID_HOME。复用仓库 wrapper，测试项目不加入正式 settings.gradle.kts。

```powershell
Copy-Item local.properties tools/network-probe/local.properties
New-Item -ItemType Directory -Force .gradle | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Get-Location).Path)\.gradle"
.\gradlew.bat -p tools/network-probe assembleDebug --no-daemon --console=plain
# 使用自己环境中的 adb；替换手机当前连接地址。
adb -s '<手机IP>:<连接端口>' install -r tools/network-probe/build/outputs/apk/debug/network-probe-debug.apk
adb -s '<手机IP>:<连接端口>' shell am start -S -n com.skycommand.networkprobe/.ProbeActivity --es computer_ipv4 '<电脑当前WLAN_IP>'
```

安装后需保持测试 App 在前台。它自动监听 UDP 39081，屏幕保持亮起；离开前台会关闭 UDP 和测试 WebSocket，返回后重新开始。此行为用于控制测试条件，不代表 Relay 的后台设计。WebSocket 测试会在电脑临时登记一个随机 network-probe 设备，握手完成即关闭；不发送业务帧，也不响应业务命令。

## 发送与取证

电脑端按 Sky-Command 的 `docs/auto-discovery/BROADCAST-TEST.md` 发送三类对照包。只处理 kind 为 SKY_COMMAND_NETWORK_PROBE、testId 为 UUID、sequence 为 1..20、大小不超过 1024 字节的包；按发送源和 testId 统计唯一序号，重复包不计入数量。测试窗口最多保留 32 组，不适合长期监听。

```powershell
adb -s '<手机IP>:<连接端口>' logcat -d -s SkyNetworkProbe:I '*:S'
```

确认 UDP_READY 后才开始发送。成功证据是相应 testId 的 UDP_RECEIVED，以及 WS_OPEN / HELLO_SENT binary / WS_PAIRED / WS_RESULT paired=true。现有协议通过二进制 WebSocket 帧承载 UTF-8 JSON，探针也按该格式发送和接收；文本帧被拒绝不能直接解释为网络不通。仅有发送成功不能证明送达。原始日志含网络地址，存放本机被忽略的 tmp 目录；分享时只提交脱敏的计数与结论。测试完成后可 force-stop 或卸载此独立 App。

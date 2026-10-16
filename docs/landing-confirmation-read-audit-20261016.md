# 确认降落标志初读排查（2026-10-16）

用户反馈似乎一直未收到“确认继续降落”。已保存的 18:57–18:58 完整实测中，该标志确实未观察到 true；不能由此断言 DJI 历来从未返回 true。此前桌面修复只解决错误的动作选择及无效确认下发，没有证明真实降落故障已经解决。

## 手机端发现与修复

`MsdkV5FlightTelemetryApi.requestInitialValue` 请求异步初读时，把尚未收到监听事件的版本记为 0；初读成功时却用 Map 缺键的 null 与该 0 比较，导致该 Key 的首值被丢弃。确认标志若仅通过首读返回 true，就可能一直保持未知。已有监听事件返回的 false 不受此缺陷解释，不能把实测的 false 全说成程序伪造。

Git blame 定位这组不一致始于 9 月 1 日的 `eb1aac06`，9 月 2 日更新了代次检查。它早于 10 月 2 日桌面监控页修改，不能独自解释用户描述的回归边界。

本次将原有首值回调接收逻辑提取为同文件内部的 `FlightTelemetryInitialValue`，在不加载 DJI Android 原生库的 JVM 中验证，然后把回调比较的缺省版本统一为 0。该代码仍由真实 `KeyManager.getValue` 完成回调调用，仍在原锁内更新事实、锁外通知，不改变其它键的值来源。

同时从既有确认标志监听/初读回调记录只读诊断，保留原始值与 event / initial / unavailable 来源。不增加同 Key 的监听器或硬件读取，不改变降落保护，不调用飞行动作。

## 链路核对

手机 `AndroidFlightTelemetrySource`、`MobileRelayGraph`、`SnapshotAssembler` 与 `TelemetryFrameMapper` 按原布尔值传递 `landingConfirmationNeeded`。桌面 `workflow-snapshot` 在飞控没有明确断开时保留 true/false，未知保持 null；没有发现把所有 true 固定映射成 false 的代码。界面和发送前的检查使用该字段，飞行模式不代替确认请求。

## 验证

- 首值回调回归先复现 9 项中 5 项预期失败；修复后 9 项通过。覆盖首次 true/false/null、较新监听事件优先、无关 Key 不干扰、失效和重建代次。
- `:telemetry:android-flight-telemetry-adapter:testDebugUnitTest`：32 项通过。
- `:telemetry:snapshot-assembler:test`：11 项通过；`:telemetry:telemetry-publisher:test`：3 项通过。
- `:app:testDebugUnitTest`：107 项通过；`:app:assembleDebug` 成功。
- 桌面遥测适配器与工作流定向测试：60 项通过。
- 这些测试验证应用的接收与传递逻辑，没有模拟固件、落点保护或实飞，未证明实际何时会出现确认请求。

## Code Review

检查了首值与事件的竞争、Key 隔离、代次失效、锁内更新与锁外通知、未知值保留及诊断对正常遥测的影响。未发现本次修改遗留的 High/Critical 问题。独立的诊断原语已有测试；本次直接记录正常遥测回调，避免为同一 Key 重复注册监听。

用户明确确认“已落地停桨，可以更新”后，修复 APK 已通过 ADB 安装成功并启动（入口 `com.skycommand.relay/.app.MainActivity`），新进程 20042，诊断 runId 为 `8542f693-1c60-403b-8ad6-ca6fede2440a`。网关已重新观测到 Relay online、MSDK ready。

新包在 21:05:19.289 实际输出 `KeyIsLandingConfirmationNeeded;source=unavailable;value=null`，证明这次初读回调为失败而非 false。此时网关的飞控、飞行与电机仍未知，不能据此推断飞控正常连接，也不能认为该 Key 永久不受支持。实际设备上是否收到 true、是否继续下降及为何退出自动降落仍待实测。本轮没有发送任何飞行动作。

安装包 SHA256：`6E49FDB6AAACD60FABE4C021A3B50E6397D9F2DBBAEB8CAA75494503F9E9FE99`。构建/测试与安装日志保存在本机临时目录 `relay-landing-investigation`，原诊断包备份未覆盖。

## 用户复测后确认链路走通（21:17）

用户反馈“似乎恢复了”后，只读抓取手机日志并核对网关遥测，未修改生产代码或发送任何飞行动作：

- 21:17:08.141：原始 `KeyIsLandingConfirmationNeeded` 监听事件返回 true（序号 145），随后状态快照也保留 true。
- 21:17:13.031：收到平台 `flight.confirm-landing`（序号 148）；请求状态中的确认标志仍为 true。
- 21:17:13.068：确认命令 succeeded，耗时 37 ms（序号 150）。
- 21:17:13.152：确认标志变回 false；随后飞行模式为 CONFIRM_LANDING。此处也再次说明该模式不等于等待用户确认。
- 21:17:16.327：快照明确 `isFlying=false;motorsOn=false;altitudeMeters=0.0`（序号 160），距离确认请求 3296 ms。
- 复查网关同样报告飞控 connected、flightState=grounded、motorsOn=false、landing.phase=confirmed-grounded。

该记录证明此次真实“需要确认 → 人工确认 → 落地停桨”链路走通，也证明 SDK 能返回该标志的 true。这次 true 来自监听事件，不能据此把此前未收到 true 或退出自动降落的全部原因归于首读缺陷。21:17:01 另有一次明确的停止自动降落请求，之后才重新发起降落，不能将该次退出误报为自行退出。

正向事件时序及请求时 true 已通过脚本断言，证据为临时目录中的 `restored-landing-logcat.log`、`restored-landing-events.json` 和 `restored-landing-assertions.json`。手机待发送事件队列已被确认清空；logcat 有异步合并与序号缺口，因此不以这份日志声称完整记录了所有事件或排除所有其它输入。本次只更新复测记录，自审没有新增飞行行为或协议改动。

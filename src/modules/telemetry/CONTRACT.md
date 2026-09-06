# telemetry 一级模块契约

状态：已实施并已验证
版本：1.0.0
所属程序：MSDK Relay Android
Gradle 路径：`:telemetry`

## 1. 唯一职责

`telemetry` 将其他模块已经公开的不可变状态转换为电脑端可读取的一次快照，并在状态变化时安全发布。它不拥有设备、航线或直播的业务事实，不直接调用 DJI，也不直接管理 WebSocket。

遥测源可能在 DJI `KeyManager.listen` 或 Android 主线程上通知变化。因此本模块必须把“采样当前完整快照”和“经 sink 写入电脑”解耦：通知调用方只提交当前发布意图并立即返回，专属后台发布出口才可以调用 sink。状态事实在源模块内仍逐项、无损地更新；仅尚未开始的、面向同一电脑会话的完整快照允许被更新的快照替换。命令回执、任务阶段与诊断事件不属于遥测快照，不得经过此替换出口。

## 2. 二级模块

| 二级模块 | 唯一职责 |
| --- | --- |
| `snapshot-assembler` | 从同一次采样的设备、飞行、直播、航线和相机编码帧观察快照产生安全遥测值，并组合公开能力值 |
| `capability-calculator` | 将内部设备能力转换为电脑端稳定能力字段 |
| `telemetry-command-handler` | 提供一次性 `telemetry.read` 结果 |
| `telemetry-publisher` | 去重、失败重试和发送顺序 |
| `android-flight-telemetry-adapter` | 观察 DJI 飞行、电量、定位、视觉与起降诊断 Key，并提供原子只读飞行遥测快照 |

## 3. 对外接口

```text
Telemetry.create(source, sink[, publicationExecutor]) -> Telemetry
telemetry.start() -> Started | AlreadyStarted
telemetry.stop() -> Stopped | AlreadyStopped
telemetry.resetPublicationBaseline() -> Unit
telemetry.read() -> ReadSucceeded(snapshot) | ReadUnavailable
telemetry.publishCurrent() -> Queued | Rejected
```

## 4. 规则

- `source` 是组合根提供的只读 `TelemetryStateSource`，负责在一个采样边界返回设备、飞行、直播、航线和相机编码帧观察快照，并在任一来源变化时通知。相机帧观察只上报元数据与连续性事实，既不是视频传输，也不改变 DJI Key、图传命令状态或任何控制门禁。telemetry 不持有或修改这些事实。
- `start()` 订阅统一状态源。每次有效状态事件只提交一次异步发布意图，不得在来源回调、DJI 回调或 Android 主线程中调用 sink；重复启动不得重复订阅。
- 对同一个活动代次，尚未进入 sink 的多个状态变化只保留最新的完整快照。已进入 sink 的一次发送按 `telemetry-publisher` 的去重与结果规则结束；它不能阻塞新的状态回调。
- `stop()` 注销订阅、失效尚未开始的旧代次快照并为下一次启动重置发布去重基线；停止后不得启动已经排队的旧事件发布。
- `resetPublicationBaseline()` 只清除已发送快照的去重基线，不订阅、注销、读取或修改任何状态源。每次电脑 WebSocket 进入一个新的 `ACTIVE` 会话，组合根必须在发布该会话首帧前调用它；因此相同的当前快照也必须发送给新会话。
- 启动不补发历史状态；连接建立后需要立即完整快照时，组合根调用 `publishCurrent()`。`Queued` 仅表示当前快照已被后台发布出口接收，不表示 WebSocket writer、桌面或页面已经收到它；未启动或采样失败时返回 `Rejected`。
- `publicationExecutor` 是只承担后台调度的公开端口；省略时使用本模块的单线程生产执行器。它不接触 DJI、设备事实、协议或 WebSocket。测试可以注入确定性执行器；生产组合根不得把 Android 主线程或 DJI 回调执行器作为该端口。
- 即时读取和持续发布都使用同一个 `SnapshotAssembler`，不存在两套字段规则。
- `TelemetrySnapshot.capabilities` 只能是 `TelemetryCapabilities`，不得暴露 `DeviceCapabilities` 或其他设备连接层内部类型。
- sink 失败不影响状态仓库或后续状态变化。sink 的网络迟滞、阻塞或失败不得等待或阻塞任何 DJI/Android/图传状态回调；后台出口至多保留一个尚未发布的最新快照。

## 5. 测试要求

覆盖启动/停止幂等、四类来源任一变化发布、同一组合快照去重、停止后不发布、重启后重新发布、即时读取、采样失败、sink 失败、阻塞 sink 下来源回调立即返回、多个阻塞期间变化只发布最终快照和监听器并发注销。

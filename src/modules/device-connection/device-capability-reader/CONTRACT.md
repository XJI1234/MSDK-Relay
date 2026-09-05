# device-capability-reader 二级模块契约

状态：已实施
版本：1.1.0
所属一级模块：`device-connection`
Gradle 路径：`:device-connection:device-capability-reader`

## 1. 唯一职责

本模块只根据一个不可变 `DeviceSnapshot` 推导手机端的操作能力与图传源当前观测。它不读取 DJI、不修改状态、不排队操作、不监听网络，也不把“可请求”或“源状态已就绪”误认为“操作已经成功”。

## 2. 对外接口

```text
DeviceCapabilityReader.read(snapshot) -> DeviceCapabilities
```

`DeviceCapabilities` 固定包含：

```text
canStartPairing
canStopPairing
canReadTelemetry
canStreamVideo
canRunWayline
```

## 3. 推导规则

- `canStartPairing`：SDK `READY`、遥控器连接、飞控明确断开，且配对为 `UNKNOWN`、`IDLE`、`PAIRED`、`FAILED` 或 `STOPPING`。飞控已连接或状态未知时必须为假。`PAIRED` 仍允许显式开始，以支持更换飞机；停止对频后也必须仍可再次开始。
- `canStopPairing`：配对为 `PAIRING`、`PAIRED` 或 `STOPPING`。
- `canReadTelemetry`：SDK `READY`、飞控连接。
- `canStreamVideo`：SDK `READY`、`AirLinkKey.KeyConnection` 已连接且 `CameraKey.KeyConnection(LEFT_OR_MAIN)` 已连接时为真。它是手机相对同一份当前快照对“生产 RTMP 图传源可用”的唯一推导，不依赖产品 Key、飞控连接、遥测、电量、航线或对频状态。生产 RTMP 的 `StreamStartGate` 必须直接复用它；任一源 Key 为 `DISCONNECTED` 或 `UNKNOWN` 时，不得调用 `ILiveStreamManager.startStream`。这是图传源专属前置条件：真机已证实，源断开时 MSDK 仍可能接受 `startStream` 并报告 `LiveStreamStatus.isStreaming=true`，却没有产生可用画面。它不表示图传已开始、RTMP 已收到有效视频或桌面正在出画；`startStream` 成功和 `LiveStreamStatus.isStreaming` 仍分别只表示 DJI 已接受调用和 DJI 报告直播会话运行。
- `canRunWayline`：SDK `READY`、遥控器连接和飞控连接。对频是否完成不进入这条能力；电脑按对频状态单独拦截启动。
- 所有字段独立按同一个输入快照推导；输入快照不会被修改。`PairingController` 必须直接复用 `canStartPairing`，不得维护第二份配对开始条件。异常或未知状态只能收紧能力，不能放宽能力。`ProductKey.KeyConnection` 仍在输入快照中作为原始诊断事实保留，但本模块不得读取它或用它授权、拒绝任何能力。

## 4. 测试要求

必须覆盖初始状态、每个前置条件单独缺失、每个配对状态、完整就绪状态、输入不可变性和能力之间不互相污染。

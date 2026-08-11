# pairing-status-link 模块契约

状态：已实现并已验证
版本：1.0.0
所属一级模块：device-connection
逻辑 Gradle 路径：:device-connection:pairing-status-link

## 唯一职责

本模块接收已标准化的遥控器真实飞行器配对状态观察，并将其写入唯一 `DeviceStateStore` 快照的配对字段。

它不发起配对、不停止配对、不调用 DJI SDK API、不调度或取消操作、不决定请求是否允许、不从飞行器或遥控器连接状态推断配对状态、不持久化状态、不发布遥测数据、不管理图传或航线任务、不建立中继连接、不请求权限，也不渲染用户界面。

## 对外接口

```text
PairingStatusPort.start(listener) -> PairingStatusSubscription
PairingStatusPort.stop() -> Unit
subscription.cancel() -> Unit

PairingStatusLink.create(store, port) -> PairingStatusLink

link.start() -> PairingStatusStartResult
link.stop() -> PairingStatusStopResult
```

端口只报告：

```text
PairingStatusSignal(sourceRevision, state)
```

`sourceRevision` 必须严格为正，并在同一端口实例的进程生命周期中随每个信号递增。`state` 为既有的平台无关 `PairingState` 枚举。端口不得暴露 DJI 键、回调、错误、Android 对象、序列号、产品 ID 或原始异常细节。

`start` 可以在返回订阅前同步调用监听器。调用方必须将该初始信号与后续每个信号完全同等对待。

## 生命周期规则

1. `PairingStatusLink.start` 最多创建一个有效端口观察。有效期间重复调用返回 `AlreadyStarted`，且不得再次调用端口。
2. `PairingStatusLink.stop` 取消保存的订阅并要求端口停止。它是幂等操作：首次调用返回 `Stopped`，后续调用返回 `AlreadyStopped`。
3. 只接受当前有效运行的信号。`start` 正在取得订阅时到达的信号必须缓冲，并在订阅安装完成后按到达顺序写入。
4. 停止后、启动失败后或来自旧运行的信号必须忽略。重启会创建新运行，只接受该运行的信号。
5. 无法创建观察时，`start` 返回 `Rejected("pairing status listener unavailable")`。失败后不得遗留有效运行或部分安装的订阅。
6. 订阅取消和 `port.stop` 失败必须被隔离，不能使运行继续有效，也不能让延迟信号进入状态存储。

## 状态规则

1. 本链接将每个被接受信号直接映射为 `DeviceStatePatch.pairing(signal.sourceRevision, signal.state)`。
2. 本链接不得翻译、伪造或拒绝有效的 `PairingState` 值。特别是不得将 `PAIRING` 认定为 `PAIRED`，不得将 `UNKNOWN` 认定为 `IDLE`，也不得因飞行器断开而改变配对状态。
3. `DeviceStateStore` 是跨来源排序的唯一权威。它返回过期版本是正常结果，不得触发重试、状态重写或诊断。
4. 无效信号，包括非正版本或写入信号时的任何失败，必须被隔离，并且只能报告为 `PairingStatusDiagnosticKind.INVALID_SIGNAL`。它们不得终止有效观察或阻止后续有效信号。
5. 启动、停止或取消期间的端口失败只能报告为 `PairingStatusDiagnosticKind.PORT_FAILURE`。诊断为尽力而为；诊断接收器失败必须被隔离。

## 依赖规则

- 本模块只依赖 `:device-connection:device-state-store`。
- 它不得依赖 `pairing-controller`、`dji-operation-coordinator`、`sdk-lifecycle`、`remote-controller-link`、`aircraft-link`、任何 Android 适配器、DJI MSDK、遥测、图传、航线任务、relay-gateway、app-runtime 或 Android UI 类型。
- 独立的 Android 适配器将实现 `PairingStatusPort`。只有该适配器可以了解 DJI 的配对状态键及其厂商专用值。
- 最终组合根在 DJI SDK 注册可用后，与其他设备观察器一起启动该链接。本链接不强制 SDK 的启动顺序。

## 验证要求

JVM 契约测试必须覆盖初始同步信号、所有 `PairingState` 值、正版本和过期版本、重复启动、停止、重复停止、订阅创建期间的信号缓冲、重启、停止及重启后的过期回调、端口启动失败、订阅和停止失败、无效信号以及诊断接收器异常隔离。

本模块没有 Android 依赖。Android 真机验证只属于后续 `android-pairing-status-adapter` 模块。

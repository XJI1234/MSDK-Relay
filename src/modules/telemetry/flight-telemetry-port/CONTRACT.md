# flight-telemetry-port 二级模块契约

状态：实施中  
所属一级模块：telemetry  
逻辑 Gradle 路径：`:telemetry:flight-telemetry-port`

## 唯一职责

本模块只定义手机端飞行遥测来源的纯 Kotlin 端口：读取最近一次完整快照、订阅变化、在飞控链路变更时作废或重新观察飞控事实、注销订阅和关闭来源。它不依赖 Android 或 DJI，不读取平台键，不校验业务能力，不发布网络帧，也不创建线程。

## 对外接口

```text
FlightTelemetrySource.snapshot() -> FlightTelemetrySnapshot
FlightTelemetrySource.onChanged(listener) -> FlightTelemetryRegistration
FlightTelemetrySource.invalidateFlightControllerFacts() -> Unit
FlightTelemetrySource.refreshFlightControllerFacts() -> Unit
FlightTelemetryRegistration.unregister() -> Unit
FlightTelemetrySource.close() -> Unit
```

`invalidateFlightControllerFacts()` 只由组合根在 MSDK 不再 `READY` 或 `FlightControllerKey.KeyConnection` 明确变为断开时调用；它必须立即清空仅属于飞控或飞控辅助的飞行状态、电机状态、飞行模式、低电量返航状态与预估时间、高度、位置、GPS、视觉和起降诊断事实，使当前飞控观察代次失效，并通知订阅者。它不得清空 `BatteryKey` 的连接事实或电量。`refreshFlightControllerFacts()` 只在 MSDK 已 `READY` 且该 Key 从非连接状态重新明确连接时调用；它必须保持飞控事实为空，建立新的观察代次，以生产适配器中的 `listen + 异步 getValue(callback)` 获得新的硬件事实。旧飞控代次回调不得重新写入快照。若重建失败，飞控事实继续为空，调用方因此保持失效关闭。

电池是独立状态域：`battery` 只投影 `BatteryKey.KeyConnection` 的 `CONNECTED`、`DISCONNECTED`、`UNKNOWN`；`batteryPercent` 只有在 `battery == CONNECTED` 且来自同一主电池索引的有效读数时才能公开。飞控断连不得改变这两个字段；电池 Key 明确断连或值未知时，电量必须立即变为未知。

快照必须不可变；`sourceGeneration` 标识当前飞控事实观察代次，`sourceRevision` 是该来源内严格单调的状态修订。飞控连接失效、重建观察或关闭时必须推进观察代次；任何监听回调造成的快照更新必须推进状态修订。这两个字段仅用于同一手机进程内关联连续 MSDK 监听事实，不映射到电脑协议，也不得被解释为 DJI Key 值。注销和关闭必须幂等。Android 适配器与跨进程验证模拟器都是该端口的实现，业务调用方不得感知实现来源。

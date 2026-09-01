# flight-telemetry-port 二级模块契约

状态：实施中  
所属一级模块：telemetry  
逻辑 Gradle 路径：`:telemetry:flight-telemetry-port`

## 唯一职责

本模块只定义手机端飞行遥测来源的纯 Kotlin 端口：读取最近一次完整快照、订阅变化、在飞控链路变更时作废或重新观察快照、注销订阅和关闭来源。它不依赖 Android 或 DJI，不读取平台键，不校验业务能力，不发布网络帧，也不创建线程。

## 对外接口

```text
FlightTelemetrySource.snapshot() -> FlightTelemetrySnapshot
FlightTelemetrySource.onChanged(listener) -> FlightTelemetryRegistration
FlightTelemetrySource.invalidate() -> Unit
FlightTelemetrySource.refresh() -> Unit
FlightTelemetryRegistration.unregister() -> Unit
FlightTelemetrySource.close() -> Unit
```

`invalidate()` 只由组合根在 `FlightControllerKey.KeyConnection` 明确变为断开时调用；它必须立即清空所有飞行事实、使当前 MSDK 观察代次失效，并通知订阅者。`refresh()` 只在该 Key 之后重新明确连接时调用；它必须保持空快照，建立新的观察代次，以生产适配器中的 `listen + 异步 getValue(callback)` 获得新的硬件事实。旧代次回调不得重新写入快照。若重建失败，快照继续为空，调用方因此保持失效关闭。

快照必须不可变；注销和关闭必须幂等。Android 适配器与跨进程验证模拟器都是该端口的实现，业务调用方不得感知实现来源。

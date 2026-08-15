# flight-telemetry-port 二级模块契约

状态：实施中  
所属一级模块：telemetry  
逻辑 Gradle 路径：`:telemetry:flight-telemetry-port`

## 唯一职责

本模块只定义手机端飞行遥测来源的纯 Kotlin 端口：读取最近一次完整快照、订阅变化、注销订阅和关闭来源。它不依赖 Android 或 DJI，不读取平台键，不校验业务能力，不发布网络帧，也不创建线程。

## 对外接口

```text
FlightTelemetrySource.snapshot() -> FlightTelemetrySnapshot
FlightTelemetrySource.onChanged(listener) -> FlightTelemetryRegistration
FlightTelemetryRegistration.unregister() -> Unit
FlightTelemetrySource.close() -> Unit
```

快照必须不可变；注销和关闭必须幂等。Android 适配器与跨进程验证模拟器都是该端口的实现，业务调用方不得感知实现来源。

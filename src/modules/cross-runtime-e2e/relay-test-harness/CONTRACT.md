# relay-test-harness 二级模块契约

状态：已实现  
所属一级模块：cross-runtime-e2e（验证模块）  
逻辑 Gradle 路径：`:cross-runtime-e2e:relay-test-harness`

## 唯一职责

本模块在独立 Kotlin/JVM 进程中装配手机端全部当前生产可达业务模块，并以正式 `OkHttpTransportConnector` 连接桌面测试宿主。它只负责测试进程的启动、受控动作、快照读取和关闭；不实现 WebSocket 协议、DJI 模拟、桌面业务或 Android 生命周期。

## 接口

```text
RelayTestHarness.start(config) -> RelayTestHarness
harness.snapshot() -> RelayHarnessSnapshot
harness.advanceSimulation(duration) -> Unit
harness.reconnect() -> Unit
harness.close() -> Unit
```

启动时必须装配 `RelayGateway`、`DeviceConnection`、`Telemetry`、`WaylineMission`、`FlightControl`、`DeviceSettings` 和 `LiveStream` 的正式实现，并且全部外部 DJI/Android 端口只能来自 `SimulationDjiAdapter`。WebSocket 只能连接回环地址。`reconnect` 必须通过正式 `AppRuntime.stop/start` 重新建立会话，不得直接伪造会话帧。关闭必须反向停止网关、遥测、业务模块、模拟器和执行器，并释放由测试宿主显式拥有的 OkHttp 线程池与连接池，且幂等。

它不证明 Android 权限、USB、Activity、真实 DJI SDK 或媒体数据流；这些由真机验收。

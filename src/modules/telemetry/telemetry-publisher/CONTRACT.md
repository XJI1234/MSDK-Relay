# telemetry-publisher 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`telemetry`
Gradle 路径：`:telemetry:telemetry-publisher`

## 1. 唯一职责

本模块只负责把组装好的遥测快照按明确的“状态变化触发”或“显式发布”策略交给发布端。它不修改设备状态、不编码协议、不管理 WebSocket、不读取 DJI。组合根将快照映射为实际 `TelemetryFrame` 时，必须为每个尝试发送的帧附加单调传输序号；该序号不属于本模块，也不是 DJI 事实。

## 2. 对外接口

```text
TelemetryPublisher.create(source, sink) -> TelemetryPublisher
publisher.publish(snapshot) -> Published | SkippedUnchanged | Rejected
publisher.reset()
```

`TelemetrySink` 只接收不可变 `TelemetrySnapshot`；发布结果不得包含网络异常或内部对象。

## 3. 规则

- 只发布当前快照与上次成功发布快照不同的值；第一次调用一定尝试发布。
- sink 接受后才更新去重基线；sink 拒绝或抛异常时不推进基线，下一次仍可重试。
- `reset()` 清除去重基线，使新连接可以再次收到完整快照。
- 不创建后台线程、定时器或隐式重试；调用方决定何时触发。
- 发布顺序等于调用顺序；模块不并发调用 sink。

## 4. 测试要求

覆盖首次发布、相同快照去重、不同快照发布、sink 拒绝后重试、sink 异常隔离、reset 后重新发布和发布顺序。

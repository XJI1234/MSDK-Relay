# dji-flight-adapter 二级模块契约

状态：实施中
Gradle 路径：`:flight-control:dji-flight-adapter`

## 唯一职责

把一个 `FlightAction` 作为共享 DJI 操作协调器中的单个操作执行，并映射为稳定终态。它拥有超时和取消语义，但不解析协议、不决定用户确认，也不依赖 Android 或 DJI SDK 类型。

## 接口

```text
DjiFlightAdapter.create(port, coordinator, timeoutMillis) -> DjiFlightAdapter
adapter.execute(action, listener) -> FlightSubmissionResult
```

`timeoutMillis` 必须在 1,000 到 60,000 毫秒之间。端口只能调用 `succeed` 或 `fail`；异常、超时、取消和重复/迟到回调都归一化为最多一次终态。协调器拒绝提交时不调用端口。

超时或已开始操作的取消并不证明飞控没有收到 DJI 调用。此时共享协调器保留唯一 DJI 操作槽位，适配器必须拒绝新的飞行写操作，直至原 DJI 回调或与该动作对应的权威状态观察确认硬件已稳定。`STOP_TAKEOFF` 与 `STOP_AUTO_LANDING` 也经同一协调器串行提交，绝不绕过队列并发访问 `KeyManager`；调用方必须让操作者显式确认，且不得把已排队或已接受解释为飞控已经悬停。

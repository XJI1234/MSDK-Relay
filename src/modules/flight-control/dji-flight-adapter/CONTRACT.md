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

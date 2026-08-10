# pairing-controller 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`device-connection`
Gradle 路径：`:device-connection:pairing-controller`

## 1. 唯一职责

本模块负责配对开始/停止请求的前置条件、状态过渡和结果转换。它不创建线程、不直接调用 DJI SDK、不管理 WebSocket，也不生成遥测 JSON。

## 2. 接口

```text
PairingController.start(timeoutMillis, listener) -> Accepted(cancellation) | Rejected(reason)
PairingController.stop(timeoutMillis, listener) -> Accepted(cancellation) | Rejected(reason)
PairingController.state() -> PairingState
```

底层 `PairingPort` 只提供两个已经封装好的 `DjiOperation`。所有执行必须交给 `dji-operation-coordinator`。

## 3. 规则

- 开始配对要求 SDK `READY`、遥控器 `CONNECTED`、飞行器 `DISCONNECTED`，且当前配对状态为 `UNKNOWN` 或 `IDLE`。
- 停止配对要求当前状态为 `PAIRING`、`PAIRED` 或 `STOPPING`。
- 开始/停止请求被接受只表示请求进入 DJI 调度队列，不表示设备已经配对或已经停止配对。
- 接受开始请求后进入 `PAIRING`；接受停止请求后进入 `STOPPING`。
- 操作成功不直接伪造 `PAIRED` 或 `IDLE`；这些状态必须由真实 DJI 配对状态观察更新。
- 操作失败、超时或取消进入 `FAILED`，并通过监听器返回稳定结果；旧操作的晚到回调不能修改新状态。
- 端口创建 DJI 操作失败时请求被拒绝，状态进入 `FAILED`；调度器拒绝请求时也不保留半完成的过渡状态。
- 时间限制与 `dji-operation-coordinator` 相同，为 `1_000..60_000` 毫秒；非法值在任何状态变更前拒绝。

## 4. 测试要求

覆盖所有前置条件、重复调用、开始/停止状态过渡、调度器成功/失败/超时/取消、真实状态观察缺失时不伪造成功，以及旧回调隔离。

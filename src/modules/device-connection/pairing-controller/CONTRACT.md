# pairing-controller 二级模块契约

状态：已实施
版本：1.1.0
所属一级模块：`device-connection`
Gradle 路径：`:device-connection:pairing-controller`

## 1. 唯一职责

本模块负责配对开始/停止请求的状态过渡和结果转换。它通过 `device-capability-reader` 取得唯一的开始前置条件，不复制连接 Key 判断。它不创建线程、不直接调用 DJI SDK、不管理 WebSocket，也不生成遥测 JSON。

## 2. 接口

```text
PairingController.start(timeoutMillis, listener) -> Accepted(cancellation) | Rejected(reason)
PairingController.stop(timeoutMillis, listener) -> Accepted(cancellation) | Rejected(reason)
PairingController.state() -> PairingState
```

底层 `PairingPort` 只提供两个已经封装好的 `DjiOperation`。所有执行必须交给 `dji-operation-coordinator`。

## 3. 规则

- 配对是新飞机或更换遥控器时的低频维护操作，不是常规连接、图传、航线或飞行控制前置条件。开始配对必须且只能使用 `DeviceCapabilityReader.read(snapshot).canStartPairing`：SDK `READY`、遥控器明确 `CONNECTED`、飞控明确 `DISCONNECTED`，且当前配对状态为 `UNKNOWN`、`IDLE`、`PAIRED`、`FAILED` 或 `STOPPING`。飞控 `CONNECTED` 或 `UNKNOWN` 时不得开始。`ProductKey.KeyConnection` 仅为诊断，绝不参与此门禁。`PAIRED` 允许重新开始，以支持更换飞机。
- 停止配对要求当前状态为 `PAIRING`、`PAIRED` 或 `STOPPING`。
- 开始/停止请求被接受只表示请求进入 DJI 调度队列，不表示设备已经配对或已经停止配对。
- 接受开始请求后进入 `PAIRING`；接受停止请求后进入 `STOPPING`。
- 开始操作成功不得写成 `PAIRED`。停止操作成功必须保持 `STOPPING`，不得写成 `IDLE`、`PAIRED` 或 `UNKNOWN`；`IDLE` 只能由配对状态观察写入。`STOPPING` 仍允许再次开始对频。失败、超时或取消进入 `FAILED`。
- 端口创建 DJI 操作失败时请求被拒绝，状态进入 `FAILED`。调度器拒绝有效请求时必须返回 `OPERATION_REJECTED`，不得误报为 `INVALID_TIMEOUT`，且不得改写已有配对状态：此时请求尚未进入 DJI 调用，只有共享 DJI 操作域暂时处于未确认隔离。端口执行 DJI action 时的同步异常不得被转换为 `OperationCompletion.fail()`；它可能发生在调用边界已开始后，必须传播给共享协调器，以隔离该次未确认的 DJI 写操作。当前配对命令结果只记录通用失败，真实配对状态仍只可由配对状态观察写入，绝不把这种失败伪造为 `PAIRED` 或 `IDLE`。
- 时间限制与 `dji-operation-coordinator` 相同，为 `1_000..60_000` 毫秒；非法值在任何状态变更前拒绝。

## 4. 测试要求

覆盖所有前置条件、重复调用、开始/停止状态过渡、调度器成功/失败/超时/取消、真实状态观察缺失时不伪造成功，以及旧回调隔离。

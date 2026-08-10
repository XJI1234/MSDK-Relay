# device-connection 一级模块契约

状态：已实施，待完整回归验证
版本：1.0.0
所属程序：MSDK Relay Android
模块标识：`device-connection`

## 1. 唯一职责

`device-connection` 是手机端对 DJI MSDK 设备事实的唯一来源，也是所有 DJI 操作的唯一串行调度入口。它向其他一级模块提供不可变设备快照、设备能力和受控操作调度，但不实现遥测格式、航线业务、直播业务、WebSocket 或 Android 页面。

## 2. 对外接口

```text
DeviceConnection.start() -> StartAccepted | AlreadyRunning | StartRejected
DeviceConnection.stop() -> Stopped | AlreadyStopped
DeviceConnection.snapshot() -> DeviceSnapshot
DeviceConnection.capabilities() -> DeviceCapabilities
DeviceConnection.onChanged(listener) -> Registration

DeviceConnection.requestPairingStart(completion) -> OperationAccepted | OperationRejected
DeviceConnection.requestPairingStop(completion) -> OperationAccepted | OperationRejected
DeviceConnection.operations().submit(action, timeoutMillis, completion)
  -> OperationAccepted(cancellation) | OperationRejected
```

`DeviceSnapshot`、`DeviceCapabilities`、`OperationResult` 和所有拒绝结果均不得包含 DJI SDK 类型、Android 类型、设备序列号、密钥、文件路径、原始异常或堆栈。

## 3. 二级模块

| 二级模块 | 唯一职责 | 公开产物 | 明确不负责 |
| --- | --- | --- | --- |
| `sdk-lifecycle` | 注册、初始化、停止 DJI SDK，并转换为安全 SDK 状态 | `SdkAvailability` | 配对、直播、航线、遥测 JSON |
| `dji-operation-coordinator` | 对所有 DJI 操作做串行、超时、取消和一次性完成保护 | `DjiOperationCoordinator` | 判断某项业务是否应执行 |
| `device-state-store` | 保存唯一、不可变、带版本的设备状态快照 | `DeviceSnapshotReader` | 读取 DJI SDK、发送网络消息 |
| `remote-controller-link` | 规范化遥控器连接和基本信息 | `RemoteControllerObservation` | 推断飞行器状态 |
| `aircraft-link` | 规范化飞行器、飞控和基础连接信息 | `AircraftObservation` | 航线或直播操作 |
| `pairing-controller` | 发起开始/停止配对并维护配对状态 | 配对操作结果和状态观察 | WebSocket、命令 ID、遥测发布 |
| `device-capability-reader` | 从当前设备状态推导可用功能 | `DeviceCapabilities` | 执行功能对应操作 |

## 4. 数据所有权与不变量

- `device-state-store` 是 SDK 可用性、遥控器连接、飞行器连接和配对状态的唯一状态拥有者。
- 所有读者只能取得不可变快照，不能持有或修改内部可变对象。
- `dji-operation-coordinator` 是直播、航线和配对执行 DJI SDK 调用的唯一调度入口；这些模块不得自行创建 DJI 操作线程。
- DJI 回调先由对应适配器规范化为公开观察值，再写入状态仓库；业务模块不得直接监听 DJI 回调。
- 每个来自 DJI 适配器的观察值必须有单调递增的来源版本。旧版本和重复版本不得回滚当前快照。
- 模块停止后取消监听、拒绝新操作，并将运行时状态发布为不可用；不会伪造设备已连接或操作成功。

## 5. 生命周期

```text
STOPPED -> STARTING -> READY
STARTING -> FAILED
READY -> STOPPED
FAILED -> STOPPED
```

`READY` 只表示设备连接模块已经可观察 DJI SDK，不表示遥控器、飞行器或配对已经就绪。`start()` 被重复调用不重启 SDK；`stop()` 被重复调用不再次注销监听。停止后到达的旧 DJI 回调必须被丢弃。

## 6. 依赖与替身

```text
device-connection -> DjiDevicePort（仅模块内的 Android/DJI 适配 seam）
device-connection -> MonotonicScheduler（操作超时）

生产环境 -> MSDK v5 adapter
JVM 测试 -> recording/in-memory port 与 manual scheduler
```

本仓库当前是 JVM 模块；MSDK v5 的实际 adapter 只能放在未来 Android 集成层并实现同一 `DjiDevicePort`。纯规则、状态、操作串行和回调隔离必须先在 JVM 测试中完成。

## 7. 交付标准

每个二级模块必须先在自身目录写 `CONTRACT.md`，再写代码和测试。完成本一级模块前，必须覆盖：SDK 启停、状态版本隔离、监听注销、遥控器与飞行器独立变化、配对前置条件、操作串行、超时、取消、重复完成回调、依赖异常，以及不泄露 DJI 细节的结果。

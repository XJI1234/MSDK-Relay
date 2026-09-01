# device-connection 一级模块契约

状态：已实施并已验证
版本：1.1.0
所属程序：MSDK Relay Android
模块标识：`device-connection`

## 1. 唯一职责

`device-connection` 是手机端对 DJI MSDK 设备事实的唯一来源，也是所有 DJI 操作的唯一串行调度入口。它向其他一级模块提供不可变设备快照、设备能力和受控操作调度，但不实现遥测格式、航线业务、直播业务、WebSocket 或 Android 页面。

## 2. 对外接口

```text
DeviceConnection.start() -> StartAccepted | AlreadyRunning | StartRejected
DeviceConnection.stop() -> Stopped | AlreadyStopped
DeviceConnection.refreshHardwareLinks()
DeviceConnection.snapshot() -> DeviceSnapshot
DeviceConnection.capabilities() -> DeviceCapabilities
DeviceConnection.onChanged(listener) -> Registration

DeviceConnection.requestPairingStart(completion) -> OperationAccepted | OperationRejected
DeviceConnection.requestPairingStop(completion) -> OperationAccepted | OperationRejected
DeviceConnection.operations().submit(action, timeoutMillis, completion)
  -> OperationAccepted(cancellation) | OperationRejected
```

`DeviceSnapshot`、`DeviceCapabilities`、`OperationResult` 和所有拒绝结果均不得包含 DJI SDK 类型、Android 类型、设备序列号、密钥、文件路径、原始异常或堆栈。

`DeviceConnectionDependencies` 可选接收 SDK 生命周期、设备状态、遥控器观察、飞行器观察和配对状态观察五类诊断接收器。它们只接收稳定事件码与安全上下文；任何接收器抛出的异常都必须被隔离，绝不能改变设备状态、启动、停止或配对命令的结果。应用组合根必须把这五类事件交给 `runtime-diagnostics`，使无线日志能定位观察链路故障。

## 3. 二级模块

| 二级模块 | 唯一职责 | 公开产物 | 明确不负责 |
| --- | --- | --- | --- |
| `sdk-lifecycle` | 注册、初始化、停止 DJI SDK，并转换为安全 SDK 状态 | `SdkAvailability` | 配对、直播、航线、遥测 JSON |
| `dji-operation-coordinator` | 对所有 DJI 操作做串行、超时、取消和一次性完成保护 | `DjiOperationCoordinator` | 判断某项业务是否应执行 |
| `device-state-store` | 保存唯一、不可变、带版本的设备状态快照 | `DeviceSnapshotReader` | 读取 DJI SDK、发送网络消息 |
| `remote-controller-link` | 规范化遥控器连接和基本信息 | `RemoteControllerObservation` | 推断飞行器状态 |
| `aircraft-link` | 规范化产品、AirLink、主相机、飞控和基础连接信息 | `AircraftObservation` | 航线或直播操作 |
| `pairing-controller` | 验证并串行提交开始/停止配对命令；只表达命令阶段 | 配对命令请求结果 | 观察真实配对结果、伪造 `PAIRED` 或 `IDLE` |
| `pairing-status-link` | 接收已规范化的真实配对状态并写入状态仓库 | `PairingStatusPort`、配对状态观察链接 | 发起或停止配对命令、推断配对状态 |
| `device-capability-reader` | 从当前设备状态推导可用功能 | `DeviceCapabilities` | 执行功能对应操作 |

## 4. 数据所有权与不变量

- `device-state-store` 是 SDK 可用性、遥控器、产品、AirLink、主相机、飞控连接和配对状态的唯一状态拥有者。每条连接事实均为三态：仅明确观察到 true 才是 `CONNECTED`，仅明确观察到 false 才是 `DISCONNECTED`，未观察、null、SDK 未就绪和停止后均为 `UNKNOWN`。每个 Android Key 适配器必须先用 `KeyManager.listen(key, holder, listener)` 建立持续订阅，再用 `KeyManager.getValue(key, callback)` 请求一次异步硬件读取；连接事实不得用同步 `getValue(key)` 的 MSDK 缓存初始化。初始硬件读取返回时，若该 Key 已收到更新的监听事件，旧初始结果必须丢弃。状态在监听仍有效且未收到反向事件时保持为当前 MSDK 事实，不因经过时间自动降级。端口重启、SDK 停止和运行代次更替必须先使旧观察失效，新的代次只有收到其初始异步 Key 回调或后续监听事件后才恢复可信事实。
- 所有读者只能取得不可变快照，不能持有或修改内部可变对象。
- `dji-operation-coordinator` 是直播、航线、配对、飞行控制和设备设置执行 DJI SDK 调用的唯一调度入口；这些模块不得自行创建 DJI 操作线程或绕开协调器并发调用 DJI。
- DJI 回调先由对应适配器规范化为公开观察值，再写入状态仓库；业务模块不得直接监听 DJI 回调。`ProductKey.KeyConnection`、`AirLinkKey.KeyConnection`、`CameraKey.KeyConnection(LEFT_OR_MAIN)` 与 `FlightControllerKey.KeyConnection` 必须分别保留，绝不相互推断或合并为“飞机已连接”。
- `pairing-controller` 在接受开始或停止命令时只能写入临时命令阶段 `PAIRING` 或 `STOPPING`。命令成功绝不代表已配对或已停止配对；`PAIRED`、`IDLE` 与 `UNKNOWN` 的设备事实必须由 `pairing-status-link` 的真实观察写入。命令失败可以安全地写入 `FAILED`，而真实观察到的 `FAILED` 同样由 `pairing-status-link` 写入。
- `pairing-status-link` 是配对事实的唯一观察入口。门面层只负责其生命周期编排，不解释配对状态、不过滤有效状态、也不接触 DJI 回调。
- 每个来自 DJI 适配器的观察值必须有单调递增的来源版本。旧版本和重复版本不得回滚当前快照。
- 模块停止后取消监听、拒绝新操作，并将运行时状态发布为不可用；不会伪造设备已连接、已断开或操作成功。

## 5. 生命周期

```text
STOPPED -> STARTING -> READY
STARTING -> FAILED
READY -> STOPPED
FAILED -> STOPPED
```

`READY` 只表示设备连接模块已经可观察 DJI SDK，不表示遥控器、飞行器或配对已经就绪。`start()` 被重复调用不重启 SDK；`stop()` 被重复调用不再次注销监听。停止后到达的旧 DJI 回调必须被丢弃。USB 授权或 SDK 稍后变为 `READY` 时，组合根可以调用 `refreshHardwareLinks()`：它只重启遥控器、飞行器和配对状态观察，不得停止或重新初始化 SDK。SDK 已 `STOPPED` 时该调用是空操作。

门面层的 `start()` 与 `stop()` 必须线性化执行：任一调用的全部启动、回滚或停止步骤完成前，另一个调用不得穿插执行。若停止请求在启动过程中到达，它必须等待该次启动完成或回滚，再执行完整停止；停止返回后，不得遗留任何有效观察链接，也不得让该次启动的后续步骤重新写入运行时状态。

门面层的组合顺序固定如下：

```text
启动：sdk-lifecycle -> remote-controller-link -> aircraft-link -> pairing-status-link
启动失败回滚：pairing-status-link -> aircraft-link -> remote-controller-link -> sdk-lifecycle
停止：pairing-status-link -> aircraft-link -> remote-controller-link -> sdk-lifecycle -> markRuntimeUnavailable
```

只有所有观察链接均成功启动，`start()` 才返回 `StartAccepted`。任一观察链接启动失败时，门面层必须停止此前已经成功启动的链接和 SDK；失败链接自身负责清理其未完成的启动，最终返回不暴露底层错误的 `StartRejected("device listener unavailable")`。回滚和停止中的清理异常必须被各链接隔离，不得阻止后续清理。停止完成后，`markRuntimeUnavailable` 必须最后执行，使快照不保留任何运行时连接或配对事实。

## 6. 依赖与替身

```text
device-connection -> DjiSdkPort、RemoteControllerPort、AircraftPort、PairingPort、PairingStatusPort（平台接缝）
device-connection -> OperationExecutor、OperationScheduler（操作调度接缝）

生产环境 -> MSDK v5 adapter
JVM 测试 -> recording/in-memory port 与 manual scheduler
```

本仓库当前是 JVM 模块；MSDK v5 的实际 adapter 只能放在 Android 集成层并分别实现对应平台接缝。纯规则、状态、操作串行和回调隔离必须先在 JVM 测试中完成。

## 7. 交付标准

每个二级模块必须先在自身目录写 `CONTRACT.md`，再写代码和测试。完成本一级模块前，必须覆盖：SDK 启停、所有观察链接的固定启动顺序、每个启动失败位置的完整回滚、停止顺序、状态版本隔离、监听注销、遥控器与飞行器独立变化、真实配对状态观察及停止后的旧回调隔离、配对前置条件、操作串行、超时、取消、重复完成回调、依赖异常，以及不泄露 DJI 细节的结果。

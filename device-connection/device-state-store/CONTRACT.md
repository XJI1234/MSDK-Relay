# device-state-store 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`device-connection`
Gradle 路径：`:device-connection:device-state-store`

## 1. 唯一职责

`device-state-store` 保存手机端唯一的、不可变的设备连接事实快照。它接收已经规范化的完整观察值，按来源版本提交新快照，并让其他模块安全读取或订阅变化。

它不读取 DJI SDK、不解析协议帧、不发布遥测、不执行配对、不保存航线或直播状态。

## 2. 对外接口

```text
DeviceStateStore.create(diagnosticSink?) -> DeviceStateStore

store.apply(observation) -> Applied(snapshot) | IgnoredStale(sourceRevision)
store.applyPairing(pairing) -> Applied(snapshot) | IgnoredStale(sourceRevision)
store.applySdk(availability) -> Applied(snapshot) | IgnoredStale(sourceRevision)
store.markRuntimeUnavailable() -> Applied(snapshot)
store.snapshot() -> DeviceSnapshot
store.onChanged(listener) -> Registration
```

`DeviceStatePatch` 必须包含来源、该来源的正整数 `sourceRevision`，以及来源负责的局部字段。来源只有 `SDK`、`REMOTE_CONTROLLER`、`AIRCRAFT`、`PAIRING` 四类；SDK 来源只更新 `sdkAvailability`，遥控器来源只更新遥控器字段，飞行器来源只更新飞行器和飞控字段，配对来源只更新 `pairing`。状态仓库是唯一合并者：每个来源独立比较版本，成功的局部更新产生一个新的全局快照版本。调用方不能读取再拼装其他来源状态。

`applyPairing` 用于需要由本程序产生的配对意图和失败状态。它在仓库锁内为配对来源分配下一个版本，因此不会与真实 DJI 配对观察争用版本号。

`applySdk` 用于 SDK 生命周期转换。它在仓库锁内为 SDK 来源分配下一个版本；只有生命周期组合层可以调用此接口。

`markRuntimeUnavailable` 由一级组合门面在停止时调用，一次性清除 SDK、遥控器、飞行器、飞控、配对和型号事实，避免停止后继续暴露上一次运行的连接状态。

`DeviceSnapshot` 固定包含：

```text
revision: Long
sdkAvailability: STOPPED | STARTING | READY | FAILED
remoteController: DISCONNECTED | CONNECTED
aircraft: DISCONNECTED | CONNECTED
flightController: DISCONNECTED | CONNECTED
pairing: UNKNOWN | IDLE | PAIRING | PAIRED | STOPPING | FAILED
remoteControllerModel: String?
aircraftModel: String?
```

型号字段只允许非空、无控制字符、最多 128 个 Unicode code point 的显示名称；不可包含序列号或认证信息。状态枚举不得用自由文本替代。

## 3. 状态与通知规则

- 初始快照的版本为 `0`，SDK 状态为 `STOPPED`，其余连接状态为 `DISCONNECTED`，配对状态为 `UNKNOWN`。
- `apply` 对有效新来源版本原子合并快照，并返回替换后的同一不可变对象。
- 同一来源的旧版本或相同版本返回 `IgnoredStale`，不改变快照，也不通知监听器。
- 每次有效替换只产生一次 `DeviceStateEvent(previous, current)`；事件顺序与成功 `apply` 顺序一致。
- `onChanged` 不补发当前快照。调用方需要当前状态时必须先调用 `snapshot()`。
- `Registration.unregister()` 幂等；返回后该监听器不得再收到已经排队但尚未执行的事件。
- 监听器异常不得阻止状态提交或其他监听器；异常不得向写入方抛出。

## 4. 并发与数据所有权

- `apply`、`snapshot`、订阅和注销允许并发调用。
- 每一时刻只有一个快照版本是当前事实；读取者永远得到一个字段组合完整的快照。
- 快照和事件都必须是不可变值对象。模块不暴露内部锁、集合、线程或 executor。
- 适配器是唯一允许调用 `apply` 的生产调用方；遥测、直播、航线和 gateway 只依赖 `snapshot`/`onChanged`。

## 5. 失败处理

| 情况 | 结果 | 状态变化 |
| --- | --- | --- |
| `sourceRevision <= 0` | `IllegalArgumentException` | 无 |
| 观察值型号字段非法 | `IllegalArgumentException` | 无 |
| 版本不大于当前快照 | `IgnoredStale` | 无 |
| 监听器抛异常 | 记录内部诊断 | 已提交快照不回滚 |
| 监听器注销与通知并发 | 注销等待在途回调结束 | 注销后不再投递 |

## 6. 测试要求

必须使用纯 JVM 测试覆盖：初始快照、有效完整替换、每个状态枚举、旧版本和重复版本、型号边界和控制字符、不可变性、监听顺序、监听器异常、注销、并发读取与写入，以及任意并发读取都不会看到无效字段组合。

## 7. 变更规则

新增可选状态字段可以向后兼容；删除字段、修改枚举含义、允许局部更新、修改版本比较规则或暴露 DJI 类型，都必须先更新本契约、一级契约、消费者契约和测试。

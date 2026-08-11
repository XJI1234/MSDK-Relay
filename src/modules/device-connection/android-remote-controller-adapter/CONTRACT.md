# android-remote-controller-adapter 模块契约

状态：已实现并已验证
版本：1.0.0
所属一级模块：device-connection
逻辑 Gradle 路径：:device-connection:android-remote-controller-adapter

## 唯一职责

本模块是既有 `RemoteControllerPort` 接缝的 Android DJI MSDK v5 实现。它只观察遥控器连接事实和可选的非敏感展示型号，并发布已标准化的 `RemoteControllerSignal`。

它不负责 SDK 注册，不保存设备状态，不观察飞行器或飞控，不推断配对状态，不执行 DJI 操作，不发布遥测数据，不管理图传或航线任务，不建立中继连接，不请求权限，也不渲染用户界面。

## 对外接口

```text
AndroidRemoteControllerPort.create() -> RemoteControllerPort

port.start(listener) -> PortSubscription
port.stop() -> Unit
subscription.cancel() -> Unit
```

工厂方法返回既有的平台无关 `RemoteControllerPort`。端口及其信号不得暴露 DJI 键、管理器、回调、错误、Android 对象、序列号、产品 ID 或原始异常细节。

`start` 最多建立一个有效的平台观察。有效期间重复调用不会额外注册 DJI 监听器，并返回空操作订阅。`stop` 与 `subscription.cancel` 均为幂等操作；二者会释放有效的平台观察并使当前回调代际失效。

## 信号规则

每个被接受的平台事实转换为：

```text
RemoteControllerSignal(sourceRevision, connected, displayModel?)
```

1. `sourceRevision` 必须严格为正，对每个已发布信号递增，且在进程存活期间不重置。
2. `connected` 只反映 MSDK 遥控器连接事实。本适配器不得从飞行器状态、USB 状态、历史值或产品标识推断该值。
3. `displayModel` 仅可在 MSDK 提供稳定且非敏感的遥控器展示名称时出现。不得从序列号、固件版本、飞行器型号、产品 ID 或异常中推导。不可用时为 `null`，断开时始终为 `null`。
4. 初始快照可以在 `start` 内同步到达；调用方必须将其视为普通信号。
5. 相同的平台值可以带着更新的版本号再次发布。跨来源排序和去重由状态存储负责，而非本适配器。

## 生命周期与失败规则

1. 每次成功的平台注册对应一个回调代际。来自已取消、已停止、已被替代或已失败代际的回调必须忽略。
2. 用户监听器在适配器锁外运行。监听器抛出的异常必须被隔离，不能阻止清理或后续信号。
3. 无法建立平台注册时，`start` 只能以稳定原因 `remote controller listener unavailable` 失败。原始 DJI 异常、消息和堆栈不得越过接缝。既有 `RemoteControllerLink` 负责将该原因转换为其已声明的拒绝结果。
4. 监听器释放失败必须被隔离，不能使代际继续有效，也不能让延迟回调到达调用方。
5. 本适配器不使用 Activity、Fragment、View、Context、网络端点、桌面协议数据、App Key 或进程级 SDK 关闭操作。

## 依赖规则

- 直接 DJI MSDK 依赖只能存在于此 Android 适配器。
- 本适配器仅为 `RemoteControllerPort`、`RemoteControllerSignal` 和 `PortSubscription` 依赖 `:device-connection:remote-controller-link`。
- 它不得依赖 `device-state-store`、`aircraft-link`、`pairing-controller`、`sdk-lifecycle`、遥测、图传、航线任务、relay-gateway、app-runtime 或 Android UI 类型。
- 最终 Android 组合根仅在 DJI SDK 注册可用后启动此端口；本模块不推断也不强制该顺序。

## 验证要求

JVM 测试必须覆盖初始连接和断开信号、可选型号标准化、变更及重启后的递增版本、重复启动、取消、停止、重复停止、同步注册回调、取消/停止/新代际后的过期回调、平台注册和释放失败，以及用户监听器异常隔离。

Android Debug 构建必须编译 MSDK v5.17 监听器封装。真实设备必须验证遥控器 USB 接入/断开、遥控器型号可用性、应用重建和监听器清理。飞行器、配对和遥测验证明确不属于本模块。

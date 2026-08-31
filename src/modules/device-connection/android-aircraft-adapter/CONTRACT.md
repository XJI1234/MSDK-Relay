# android-aircraft-adapter 模块契约

状态：已实现并已验证
版本：1.0.0
所属一级模块：device-connection
逻辑 Gradle 路径：:device-connection:android-aircraft-adapter

## 唯一职责

本模块是既有 `AircraftPort` 接缝的 Android DJI MSDK v5 实现。它只观察飞行器连接、飞行控制器连接和可选的非敏感飞行器展示型号，并发布已标准化的 `AircraftSignal`。

它不负责 DJI SDK 注册，不保存设备状态，不观察遥控器，不推断配对状态，不执行 DJI 操作，不发布遥测数据，不管理图传或航线任务，不建立中继连接，不请求权限，也不渲染用户界面。

## 对外接口

```text
AndroidAircraftPort.create() -> AircraftPort

port.start(listener) -> AircraftPortSubscription
port.stop() -> Unit
subscription.cancel() -> Unit
```

工厂方法返回既有的平台无关 `AircraftPort`。端口及其信号不得暴露 DJI 键、管理器、回调、错误、Android 对象、序列号、产品 ID 或原始异常细节。

`start` 最多建立一个有效的 MSDK 观察。重复调用不会额外注册 MSDK 监听器，也不会替换原监听器，并返回空操作订阅。`stop` 与 `subscription.cancel` 均为幂等操作；二者都会使当前回调代际失效并释放平台监听器。

## 信号规则

每个被接受的平台事实转换为：

```text
AircraftSignal(sourceRevision, aircraftConnected, flightControllerConnected, displayModel?)
```

1. `sourceRevision` 必须严格为正，对每个已发布信号递增，且在进程存活期间不重置。
2. `aircraftConnected` 与 `flightControllerConnected` 都是三态观察事实：MSDK 的明确 true、明确 false、null 分别表示已连接、已断开、尚未观察。`aircraftConnected` 只映射 `ProductKey.KeyConnection`，不得由飞控键推断或覆盖。`flightControllerConnected` 在产品明确连接时只映射 `FlightControllerKey.KeyConnection`；产品明确断开时强制为 false，产品未知时强制为 null。不得把 null 压成 false，也不得保留前一次连接值。
3. 飞行器明确已连接可以合法地与飞控明确断开或未知同时出现；它表示 DJI 仍确认硬件产品在线，但飞控能力尚不可用于需要飞控的操作。飞控明确断开不得使飞行器连接变为断开。
4. `displayModel` 仅可来自稳定且非敏感的 MSDK 产品类型值。不得从序列号、固件版本、遥控器类型、产品 ID 或异常中推导。不可用时为 `null`；产品不是明确已连接时必须为 `null`，不得由飞控状态清空。
5. `start` 成功后必须先注册 `ProductKey.KeyConnection`、`FlightControllerKey.KeyConnection` 和 `ProductKey.KeyProductType` 的持续监听，再读取这三个 Key 的当前值并发布一个普通初始信号。读取失败或 MSDK 返回 null 必须如实发布为未知，不能沿用旧值或压成断开。注册期间同步到达的事件必须在初始读取之后按到达顺序重放，不能被初始读取反向覆盖。
6. 相同的平台值可以带着更新的版本号再次发布。跨来源排序和去重由状态存储负责，而非本模块。

## 生命周期与失败规则

1. 每次成功的平台注册对应一个回调代际。来自已取消、已停止、已被替代或已失败代际的回调必须忽略。
2. 用户监听器在适配器锁外运行。监听器抛出的异常必须被隔离，不能阻止清理或后续信号。
3. 无法建立平台注册时，`start` 只能以稳定原因 `aircraft listener unavailable` 失败。原始 DJI 异常、消息和堆栈不得越过接缝。既有 `AircraftLink` 负责将该原因转换为其已声明的拒绝结果。
4. 平台监听器释放失败必须被隔离，不能使代际继续有效，也不能让延迟回调到达调用方。
5. 本适配器不使用 Activity、Fragment、View、Context、网络端点、桌面协议数据、App Key 或进程级 SDK 关闭操作。

## 依赖规则

- 直接 DJI MSDK 依赖只能存在于此 Android 适配器。
- 本适配器仅为 `AircraftPort`、`AircraftSignal` 和 `AircraftPortSubscription` 依赖 `:device-connection:aircraft-link`。
- 它不得依赖 `device-state-store`、`remote-controller-link`、`pairing-controller`、`sdk-lifecycle`、遥测、图传、航线任务、relay-gateway、app-runtime 或 Android UI 类型。
- 最终 Android 组合根仅在 DJI SDK 注册可用后启动此端口；本模块不推断也不强制该顺序。

## 验证要求

JVM 测试必须覆盖初始连接和断开事实、型号标准化、飞行器断开时的飞控标准化、变更及重启后的递增版本、重复启动、取消、停止、重复停止、同步注册回调、取消/停止/新代际后的过期回调、平台注册和释放失败，以及用户监听器异常隔离。

Android Debug 构建必须编译 MSDK v5.17 监听器封装。真实设备必须验证飞行器和飞控的接入/断开、仅遥控器接入时飞行器为未连接、产品型号可用性、应用重建和监听器清理。遥控器、配对、遥测、图传及航线验证均不属于本模块。

# android-flight-telemetry-adapter 模块契约

状态：已实现
版本：1.0.0
所属一级模块：telemetry
逻辑 Gradle 路径：`:telemetry:android-flight-telemetry-adapter`

## 唯一职责

本模块是 DJI MSDK v5 飞行事实到 `FlightTelemetrySnapshot` 的唯一 Android 适配器。它只观察飞行状态、电机状态、飞行模式、聚合电量、预计剩余飞行时间、相对高度和飞行器经纬度，并向组合根提供一个原子只读快照和变化通知。

它不判断设备连接，不拥有业务遥测，不发布网络消息，不控制飞行，不管理直播或航线，不注册 DJI SDK，不请求权限，也不渲染界面。

## 对外接口

```text
AndroidFlightTelemetrySource.create() -> FlightTelemetrySource

source.snapshot() -> FlightTelemetrySnapshot
source.onChanged(listener) -> FlightTelemetryRegistration
registration.unregister() -> Unit
source.close() -> Unit
```

`snapshot()` 始终返回最近一次完整、不可变且经过校验的快照。`onChanged` 最多建立一个 DJI 观察代次；重复订阅不得替换原监听器，并返回空操作注册。所有 KeyManager 监听必须关闭 DJI 的逐键自动首值回调，再由一次同步完整读取形成唯一初始变化通知，禁止先发布完整快照后又发布多帧部分初值。`unregister` 和 `close` 均幂等，并使该代次的全部迟到回调失效。

## 字段规则

1. `isFlying`、`motorsOn` 仅来自对应 DJI 飞控键；平台没有有效值时为 `null`。
2. `flightMode` 仅来自 DJI 飞行模式的稳定名称；未知、空白、控制字符或超长值为 `null`。
3. `batteryPercent` 仅接受 `0..100`；其他值为 `null`。
4. `remainingFlightTimeSeconds` 仅接受非负秒数；其他值为 `null`。
5. `altitudeMeters` 仅接受有限数值；其他值为 `null`。
6. 纬度和经度必须同时有限且分别位于 `-90..90`、`-180..180`；任一无效时两者同时为 `null`。
7. 一次平台回调必须先生成新的完整快照，再在锁外通知调用方。监听器异常不得破坏后续更新或清理。

## 生命周期与失败

- 平台注册失败统一抛出 `IllegalStateException("flight telemetry listener unavailable")`，不得泄露 DJI 异常、消息或堆栈。
- 平台释放失败必须被隔离；逻辑代次仍立即失效。
- 注册过程中的同步回调属于当前代次；取消、关闭或新代次之后到达的旧回调必须忽略。
- 模块不得创建线程、计时器、Activity、Service 或持久化状态。

## 依赖规则

- 仅依赖 `:telemetry:snapshot-assembler` 和 DJI MSDK v5.17。
- DJI 类型只能出现在本模块内部平台实现中，不得进入公开接口。
- 不得依赖其他一级模块、Android UI、relay-gateway 或 app-runtime。

## 验证要求

JVM 测试必须覆盖初始值、每个字段更新、无效值归一化、重复订阅、取消、关闭、重启、同步回调、迟到回调、注册和释放失败、监听器异常。Android Debug 构建必须编译真实 KeyManager 键监听。真实设备仍需验证键可用性、断连后的空值行为和监听清理。

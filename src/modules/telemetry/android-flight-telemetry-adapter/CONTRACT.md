# android-flight-telemetry-adapter 模块契约

状态：已实现
版本：1.0.0
所属一级模块：telemetry
逻辑 Gradle 路径：`:telemetry:android-flight-telemetry-adapter`

## 唯一职责

本模块是 DJI MSDK v5 飞行、主电池、GPS、视觉和起降诊断事实到 `FlightTelemetrySnapshot` 的唯一 Android 适配器。它只观察飞行状态、电机状态、飞行模式、主电池连接与电量、低电量返航状态与预估时间、相对高度、飞行器经纬度、GPS 信号/卫星数、视觉传感器使用、视觉系统告警、视觉定位开关、降落保护、降落确认请求、起飞失败原因和电机启动失败原因，并向组合根提供一个原子只读快照和变化通知。

它不判断设备连接，不拥有业务遥测，不发布网络消息，不控制飞行，不管理直播或航线，不注册 DJI SDK，不请求权限，也不渲染界面。

## 对外接口

```text
AndroidFlightTelemetrySource.create() -> FlightTelemetrySource

source.snapshot() -> FlightTelemetrySnapshot
source.onChanged(listener) -> FlightTelemetryRegistration
source.invalidateFlightControllerFacts() -> Unit
source.refreshFlightControllerFacts() -> Unit
registration.unregister() -> Unit
source.close() -> Unit
```

`snapshot()` 始终返回最近一次完整、不可变且经过校验的快照。`onChanged` 最多建立一个 DJI 观察代次；重复订阅不得替换原监听器，并返回空操作注册。每个 Key 必须先注册持续监听，再通过 `KeyManager.getValue(key, callback)` 对同一 Key 向硬件异步读取一次初值；不得调用同步 `getValue(key)`，因为其只读取 MSDK 缓存。初读成功前对应字段保持未知，初读失败或硬件返回 null 也保持未知，绝不能以缓存或其他字段填充。每个初读记录该 Key 的事件版本，请求之后先到达的监听事件优先，较晚返回的初读结果必须丢弃。每次被接受的平台回调都产生一份完整快照，尚未知的字段如实为 `null`；不得用缓存拼装伪完整首帧。`unregister` 和 `close` 均幂等，并使该代次的全部迟到回调失效。

组合根在飞控 Key 明确断开时调用 `invalidateFlightControllerFacts()`：适配器必须同步清空仅属于飞控或飞控辅助的字段并增加飞控观察代次，旧飞控监听和旧异步首读从此无权恢复这些字段。该方法不读取 MSDK 缓存，也不发起飞行操作，且不得清空或停止电池观察。该 Key 从非连接状态转为明确连接时，组合根调用 `refreshFlightControllerFacts()`；适配器先维持飞控事实为空，再建立新代次的飞控 `listen + 异步 getValue(callback)` 观察，并在新值到达后逐项发布。重建期间或失败后飞控字段保持未知，不能重新公开断开前坐标、飞行状态、GPS、视觉或起降诊断。该调用还必须在不取消电池监听、不清空既有电池事实的前提下，对 `BatteryKey.KeyConnection(LEFT_OR_MAIN)` 发起一次新的异步硬件读取；只有该 Key 明确连接后才能再读取电量。这样电池 Key 初读早于机载硬件就绪时可恢复，但飞控状态绝不被用来推断电池状态。

## 字段规则

1. `isFlying`、`motorsOn` 仅来自对应 DJI 飞控键；平台没有有效值时为 `null`。
2. `flightMode` 仅来自 DJI 飞行模式的稳定名称；MSDK 明确返回的 `UNKNOWN` 必须原样保留，使上层可区分“MSDK 返回未知”与“尚未取得值”。空值、空白、控制字符、超长或未识别值为 `null`。
3. `battery` 仅来自 `BatteryKey.KeyConnection` 的 `LEFT_OR_MAIN` 电池索引。`true` 为 `CONNECTED`，`false` 为 `DISCONNECTED`，空值或尚未观察为 `UNKNOWN`。这是电池是否已装入飞行器的唯一判断依据，不得以飞控、遥控器、产品、AirLink 或相机 Key 代替。飞控连接转换只可以触发对该 Key 的重新读取，不能直接写入或推断这个字段。
4. `batteryPercent` 仅来自 `BatteryKey.KeyChargeRemainingInPercent` 的 `LEFT_OR_MAIN` 电池索引，表示当前飞行器主电池的剩余百分比。不得把 `AGGREGATION` 索引用于该字段；多电池产品的总电量必须在专门的聚合策略中按各电池容量计算，不能在本模块推测。仅接受 `0..100`，且只有 `battery == CONNECTED` 时可公开；其他情况为 `null`。
5. `lowBatteryRthState` 与 `remainingFlightTimeSeconds` 必须从同一个 `KeyLowBatteryRTHInfo` 原子读取。DJI `IDLE`、`COUNTING_DOWN`、`EXECUTED`、`CANCELLED` 与明确返回的 `UNKNOWN` 都必须原样映射为公开状态；null 或未识别值为 `null`。
6. `remainingFlightTimeSeconds` 仅来自同一对象的 `remainingFlightTime`，表示 DJI 低电量返航策略下的预估时间，不能表示通用“预计可飞时间”，也绝不参与任何安全门禁。只有返航状态不是 `UNKNOWN` 且值为正秒数时才保留；DJI 默认组合 `UNKNOWN + 0` 必须保留状态 `UNKNOWN`，但预估时间为未知，不能显示为“0 分 0 秒”。
7. `altitudeMeters` 仅来自 `FlightControllerKey.KeyAltitude` 的有限原始数值，表示相对起飞点高度；本模块不得换算为海拔、下视测距高度或手动抬升高度。其他值为 `null`。
8. 纬度和经度必须同时有限且分别位于 `-90..90`、`-180..180`；任一无效时两者同时为 `null`。
9. `gpsSignalLevel`、`visionSystemWarning`、`landingProtectionState`、`takeoffFailureError` 与 `motorStartFailureError` 仅透传各自 MSDK 枚举的 `.name`。MSDK 明确返回的 `UNKNOWN`、`NONE`、`INVALID` 或其他枚举值必须原样保留；空值为 `null`，不得被翻译成安全、故障或其他派生判断。
10. `gpsSatelliteCount` 仅来自 `FlightControllerKey.KeyGPSSatelliteCount` 的非负整数原值；`visionSensorUsed`、`visionPositioningEnabled` 和 `landingConfirmationNeeded` 分别仅来自 `FlightControllerKey.KeyIsVisionSensorUsed`、`FlightAssistantKey.KeyVisionPositioningEnabled` 与 `FlightControllerKey.KeyIsLandingConfirmationNeeded` 的布尔原值。空值保持 `null`。视觉定位开关不等于视觉系统正在工作，也不等于起飞或降落安全。
11. 一次平台回调必须先生成新的完整快照，再在锁外通知调用方。监听器异常不得破坏后续更新或清理。

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

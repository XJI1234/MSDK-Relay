# Sky Command DJI Mobile SDK V5.17 参考手册

> **用途**：这是 Sky Command 手机端对 DJI Mobile SDK（MSDK）进行修改、排错和代码评审前的唯一 SDK 参考入口。它只记录当前 Android APK 实际直接调用的 API，不是 DJI 全量 SDK 文档，也不替代 DJI 的飞行安全说明。
>
> **版本锚点**：`com.dji:dji-sdk-v5-aircraft:5.17.0`，以及编译期 `com.dji:dji-sdk-v5-aircraft-provided:5.17.0`。任何升级版本后，都必须先复核本手册的“升级检查表”，不能把本文件的结论直接带到新版本。
>
> **最后核对**：2026-08-31。行为来源是 DJI 官方 API Reference；API 名称、泛型和可用方法同时以本仓库已解析的 DJI 官方 `5.17.0` 二进制为准。项目代码只说明“本项目怎样使用”，不定义 DJI API 的原始含义。

## 目录

1. [范围、来源与使用规则](#范围来源与使用规则)
2. [按项目功能反查](#按项目功能反查)
3. [初始化、注册与 MSDK 生命周期](#初始化注册与-msdk-生命周期)
4. [KeyManager：读取、订阅、写入和动作](#keymanager读取订阅写入和动作)
5. [连接、产品、遥控器与对频](#连接产品遥控器与对频)
6. [飞控与遥测](#飞控与遥测)
7. [RTMP 图传与原始相机码流](#rtmp-图传与原始相机码流)
8. [DJI Wayline 航线](#dji-wayline-航线)
9. [直接飞行控制](#直接飞行控制)
10. [相机与链路设置](#相机与链路设置)
11. [错误、型号差异和控制边界](#错误型号差异和控制边界)
12. [当前调用总表与升级检查表](#当前调用总表与升级检查表)
13. [官方来源](#官方来源)

## 范围、来源与使用规则

### 收录范围

本手册只覆盖下列 **DJI MSDK V5** 直接调用。Electron、WebSocket、RTMP 接收、Node Media Server、HTTP-FLV、flv.js、Cesium，以及桌面端的业务门禁不属于本手册。

| 官方分类 | 当前实际使用的类型或 Key | 项目职责 |
| --- | --- | --- |
| SDK Manager | `SDKManager`、`SDKManagerCallback`、`DJISDKInitEvent` | 进程级初始化与 App Key 注册 |
| Key Manager | `KeyManager`、`KeyTools`、`DJIKeyInfo`、`DJIActionKeyInfo` | 状态读取、订阅、设置和动作 |
| Product | `ProductKey.KeyConnection`、`KeyProductType` | 硬件产品事实 |
| Remote Controller | `RemoteControllerKey.KeyConnection`、`KeyRemoteControllerType`、配对 Key | 遥控器与对频事实/操作 |
| Flight Controller | 连接、飞行、电机、模式、电量返航、位置、高度和飞行动作 Key | 飞控事实、遥测与直接控制 |
| Battery | `BatteryKey.KeyChargeRemainingInPercent` | 主电池百分比 |
| Media Data Center | `ILiveStreamManager`、`ICameraStreamManager` | RTMP 推流与封存的原始码流路径 |
| Waypoint Mission | `WaypointMissionManager` / `IWaypointMissionManager` | KMZ/WPML 上传与航线控制 |
| Camera / AirLink | 两个 `KeyConnection`、相机曝光/焦点、链路频段/频道/带宽 Key | 图传源连接事实与设备设置 |
| Common callbacks | `CommonCallbacks`、`IDJIError` | DJI 异步成功/失败边界 |

### 证据优先级

1. **DJI 官方 API Reference**：决定 API 做什么、参数/回调的原始语义、可用操作和支持版本。
2. **本仓库已解析的 DJI 官方 5.17.0 依赖**：决定本 APK 实际可编译的类名、泛型、枚举成员和方法签名。坐标写在各 Android DJI 适配器的 `build.gradle.kts` 中。
3. **本仓库的 `CONTRACT.md` 与适配器代码**：只决定 Sky Command 的封装、生命周期和安全门禁，不能改变第 1、2 项的含义。
4. 真机观察只能说明某型号/固件当时的表现；它不能推翻 API 原义，也不能外推到其他型号。

### 固定术语

| 术语 | 本手册中的精确定义 |
| --- | --- |
| **MSDK 已注册** | `SDKManagerCallback.onRegisterSuccess()` 已到达，或 `isRegistered()` 返回 `true`。它不是任何具体硬件已连接。 |
| **MSDK 缓存值** | `KeyManager.getValue(key)` 的同步返回值。DJI 官方明确说明它来自 MSDK cache。 |
| **异步硬件读取** | `KeyManager.getValue(key, callback)`。DJI 官方明确说明它从 hardware device 获取值。 |
| **订阅值** | `KeyManager.listen(...)` 的回调值。连接状态适配器先建立持续订阅，再调用 `getValue(key, callback)` 请求一次异步硬件读取；订阅是状态变化通道，不是对业务含义的二次推断。 |
| **未知** | `null`、`UNKNOWN`、不支持、注册前、或尚未从 MSDK 得到可解释值。未知绝不等同于 `false`。 |
| **成功回调** | DJI 接受并完成该 API 请求的结果。对于“启动/停止/上传”类操作，它不自动证明后续运行阶段已经持续正常。 |

### 版本名称演进：不可自动替换

本项目锁定的 `5.17.0` 二进制中仍可解析 `FlightControllerKey.KeyFCFlightMode`、`KeyAltitude`、`KeyAircraftLocation` 等成员。DJI 在线 API 页面某些位置展示了较新的同类名称，例如 `KeyFlightMode` 或 `KeyAircraftLocation3D`。这不授权把项目中的旧名批量替换成新名。

升级或重命名时必须同时完成：确认目标版本公开成员、检查返回类型/索引/能力标记、更新适配器契约、添加版本定向的编译测试，并在真机验证后再合入。

## 按项目功能反查

| 我准备修改或排查什么 | 先查本手册 | 当前唯一 Android 适配器 |
| --- | --- | --- |
| App Key、SDK 无法就绪、产品连接回调 | [初始化、注册](#初始化注册与-msdk-生命周期) | `src/modules/device-connection/android-dji-sdk-adapter/.../MsdkV5ManagerBridge.kt` |
| “已连接/未知/断开”为什么不同 | [连接、产品、遥控器与对频](#连接产品遥控器与对频) | `android-aircraft-adapter`、`android-remote-controller-adapter` |
| 电量、位置、高度、飞行模式、电机 | [飞控与遥测](#飞控与遥测) | `src/modules/telemetry/android-flight-telemetry-adapter/.../MsdkV5FlightTelemetryApi.kt` |
| RTMP 开始、停止、码率、清晰度、推流状态 | [RTMP 图传](#rtmp-图传) | `src/modules/live-stream/android-dji-stream-adapter/.../MsdkV5LiveStreamApi.kt` |
| 原始 H.264/H.265 帧 | [原始相机码流](#原始相机码流封存路径) | `src/modules/live-stream/android-camera-stream-adapter/.../AndroidCameraStreamApi.kt` |
| KMZ 上传、启动、暂停、继续、停止航线 | [DJI Wayline 航线](#dji-wayline-航线) | `src/modules/wayline-mission/android-dji-wayline-adapter/.../MsdkV5WaypointMissionApi.kt` |
| 自动起飞、降落、返航 | [直接飞行控制](#直接飞行控制) | `src/modules/flight-control/android-dji-flight-adapter/.../MsdkV5FlightApi.kt` |
| 对频 | [对频状态与动作](#对频状态与动作) | `android-pairing-status-adapter`、`android-pairing-command-adapter` |
| 相机曝光/焦点、链路频段/带宽 | [相机与链路设置](#相机与链路设置) | `src/modules/device-settings/android-dji-settings-adapter/.../MsdkV5SettingsApi.kt` |

## 初始化、注册与 MSDK 生命周期

### 进程级前置条件

当前 APK 使用 DJI Aircraft 包。`DjiSdkApplication.attachBaseContext` 在 Android 进程最早期调用 `com.cySdkyc.clx.Helper.install(this)`；Manifest 提供 `com.dji.sdk.API_KEY`；随后才允许创建 DJI 适配器。详情见 `src/modules/device-connection/android-dji-sdk-adapter/CONTRACT.md`。

这三项都是 **SDK 可用的前置条件**，不是“飞机已经开机”或“可以起飞”的证据。App Key 不得写入日志、文档示例或默认值。

### `SDKManager` / `ISDKManager`

**官方包名**：`dji.v5.manager.SDKManager`、`dji.v5.manager.interfaces.ISDKManager`
**项目位置**：`src/modules/device-connection/android-dji-sdk-adapter/src/main/kotlin/com/skycommand/relay/device/sdk/android/MsdkV5ManagerBridge.kt`

| 方法/回调 | 官方原义 | 当前项目使用 | 不可推断 |
| --- | --- | --- | --- |
| `init(Context, SDKManagerCallback)` | 初始化 MSDK 内部模块；完成后必须等待 `INITIALIZE_COMPLETE` 再注册。MSDK 会强引用回调，回调不得持有短生命周期 `Activity`/`Fragment`。 | 仅传应用级 `Context`，桥接 `onInitProcess`。 | 已 `init` 不等于已注册，更不等于硬件/飞控可用。 |
| `onInitProcess(INITIALIZE_COMPLETE, ...)` | MSDK 初始化完成事件。 | 唯一允许调用 `registerApp()` 的时点。 | 其他进度事件不能触发注册。 |
| `registerApp()` | 使用开发者 App Key 完成注册；成功后 MSDK 会自动开始连接硬件。 | 初始化完成后调用一次。 | 注册成功不保证马上发现任何硬件。 |
| `onRegisterSuccess()` | MSDK 注册成功。 | 映射为 `SdkAvailability.READY`。 | 只代表 SDK 生命周期可用；不能代替遥控器、飞机或飞控 Key。 |
| `onRegisterFailure(IDJIError)` | 注册完成但发生错误。 | 安全映射为初始化失败，不泄露原始错误文本到业务协议。 | 不能重标为“飞机断开”。 |
| `onProductConnect(int)` / `onProductDisconnect(int)` | 官方定义为硬件产品连接/断开回调。 | 仅记诊断，不作为 UI 或门禁的唯一事实源。 | `productId` 不是飞控健康、图传播放或航线状态。 |
| `isRegistered()` | `true` 表示 MSDK 已成功注册。 | 仅可用于诊断或生命周期交叉检查。 | 不是“当前飞机已连接”。 |

**生命周期顺序**：`Helper.install` -> `SDKManager.init` -> `onInitProcess(INITIALIZE_COMPLETE)` -> `registerApp` -> `onRegisterSuccess` -> 才允许调用需要 MSDK 的业务 API。收到失败、关闭或旧代次回调时，当前适配器必须丢弃过期回调。

**官方来源**：[SDK Manager](https://developer.dji.com/api-reference-v5/android-api/Components/SDKManager/DJISDKManager.html)；[SDKManagerCallback](https://developer.dji.com/api-reference-v5/android-api/Components/SDKManager/ISDKManager_SDKManagerCallback.html)。

## KeyManager：读取、订阅、写入和动作

### `KeyManager` / `IKeyManager`

**官方包名**：`dji.v5.manager.KeyManager`、`dji.v5.manager.interfaces.IKeyManager`
**作用**：DJI 的 Key-Value 入口。Key 的能力由其 `DJIKeyInfo` / `DJIActionKeyInfo` 决定；不是所有 Key 都可读、可写、可订阅或可执行动作。

| API | 官方原义 | 必须遵守的规则 |
| --- | --- | --- |
| `KeyTools.createKey(info[, componentIndex])` | 把 Key 元信息和可选组件索引变成可操作 `DJIKey`。 | 相机/电池必须显式选择正确 `ComponentIndexType`；不能让“主相机”和“汇总电池”混用。 |
| `getValue(key)` | **同步读取 MSDK cache**。无可读缓存时可以得到 `null`。 | 只能作为当前 MSDK 已缓存的快照；不得声称它是一次向飞机重读，也不能把 `null` 补成 `false`。 |
| `getValue(key, defaultValue)` | 同步读缓存；读不到时返回调用者给的默认值。 | 对连接/飞行安全事实禁止使用 `false`、`0` 等业务默认值，否则会把未知伪造成已确认状态。当前生产状态适配器不使用此重载。 |
| `getValue(key, callback)` | **异步从硬件设备获取**该 Key 的值。 | 需要一次明确的硬件查询时使用；成功/失败都要处理，且不得与旧订阅代次混淆。 |
| `listen(key, holder, listener)` | 订阅 Key 的值。 | 每个适配器持有私有 `holder`；关闭时必须匹配 `cancelListen(holder)`。监听回调值可为 `null`，仍表示未知。 |
| `listen(key, holder, true, listener)` | 订阅并异步取得一次值。 | 官方文档未将这次读取明确描述为“从硬件设备获取值”；生产状态链路不用此重载作为可信初值，而是普通持续订阅加显式 `getValue(key, callback)`。 |
| `cancelListen(key, holder)` | 取消一个 Key 与一个 holder 的监听。 | 需要精确释放单个订阅时使用。 |
| `cancelListen(holder)` | 取消该 holder 的所有监听。 | 当前连接与遥测适配器使用此方式；`close()` 必须幂等。 |
| `setValue(key, value, callback)` | 设置一个可写 Key。 | 仅对 `canSet` 的 Key 使用；成功后仍应读取/订阅确认值，不能假定本地请求已经成为设备事实。 |
| `performAction(actionKey, callback)` | 执行一个可动作 Key。 | 动作 Key 通常不可 `get/set/listen`；成功只是该动作请求的成功结果，后续状态由对应状态 Key/Manager 观察。 |
| `isKeySupported(key)` | 判断当前产品是否支持 Key。 | 新增或升级 Key 时先检查；不支持、无值和断开要分别处理。 |

### 回调与错误

`CommonCallbacks.CompletionCallback` 只提供 `onSuccess()` / `onFailure(IDJIError)`；`CompletionCallbackWithParam<T>` 在成功时携带 `T`。`IDJIError` 是 DJI 的错误对象。项目业务协议统一将其归一化为安全错误类型，不透传原始 DJI 文本或堆栈。

不要把任何一个 callback 的成功解释为下游系统已经完成。例如：`startStream` 成功不证明桌面 HTTP-FLV 已播放；`pushKMZFileToAircraft` 成功不证明航线已执行；`KeyStartTakeoff` 成功不证明飞机已经离地。

**官方来源**：[IKeyManager](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/IKeyManager.html)；[CommonCallbacks](https://developer.dji.com/api-reference-v5/android-api/Components/DJICommonCallbacks/DJICommonCallbacks.html)。

## 连接、产品、遥控器与对频

### 五个连接 Key 必须独立保留

| Key | 官方类型与能力 | 官方原义 | 正确显示 | 绝不代表 |
| --- | --- | --- | --- | --- |
| `ProductKey.KeyConnection` | `Boolean`；`get/listen` | `true` 表示 **hardware product is connected**。 | `产品 Key：true / false / 未知` | 不是“飞机已开机”、不是“遥控器到飞机物理链路已确认”、不是“飞控可用”。真机中飞机关闭后它仍可能为 `true`，应如实显示原值。 |
| `RemoteControllerKey.KeyConnection` | `Boolean`；`get/listen` | `true` 表示 **remote controller is connected**。 | `遥控器 Key：true / false / 未知` | 官方描述没有把它定义为“遥控器到飞机无线链路已建立”；不能用它单独授权飞行或航线。 |
| `AirLinkKey.KeyConnection` | `Boolean`；`get/listen` | `true` 表示 AirLink 组件已连接。 | `AirLink Key：true / false / 未知` | 不是飞控状态、不是 RTMP 已推流、不是桌面播放器正在播放。 |
| `CameraKey.KeyConnection(LEFT_OR_MAIN)` | `Boolean`；`get/listen` | `true` 表示所选主/左相机组件已连接。 | `主相机 Key：true / false / 未知` | 不是自动对焦完成、不是已经产生桌面画面、不能由其他相机索引推断。 |
| `FlightControllerKey.KeyConnection` | `Boolean`；`get/listen` | `true` 表示 **flight controller is connected**。 | `飞控 Key：true / false / 未知` | 不代表 SDK 已注册，不代表图传已推流，也不能被 `ProductKey` 的值替换。 |

对所有五个 `Boolean?`：`true` 只保留上述官方肯定语义；`false` 是 MSDK 提供的否定值；`null` 是 **MSDK 当前没有可解释的 Boolean 值**。`null` 的原因不能由 UI 猜测，可能涉及缓存尚未形成、组件不可用、连接变更或型号/固件差异。因此它必须显示“未知”，并禁止依赖该 Key 的开始型操作。

当前产品、AirLink、主相机、飞控和遥控器连接适配器均先建立 `listen(key, holder, listener)` 持续观察，再调用官方明确说明“从硬件设备获取值”的 `getValue(key, callback)`。适配器不得用同步 `getValue(key)` 的 MSDK 缓存作为初始连接事实。首次硬件回调前显示未知，之后由监听器持续更新；若初始读取期间已收到该 Key 的监听更新，则丢弃较晚到达的初始读取结果。

**项目位置**：

- 产品/AirLink/主相机/飞控/型号：`src/modules/device-connection/android-aircraft-adapter/src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt`
- 遥控器：`src/modules/device-connection/android-remote-controller-adapter/src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt`

### 产品与遥控器类型

| Key | 类型、能力 | 官方原义 | 项目使用与注意事项 |
| --- | --- | --- | --- |
| `ProductKey.KeyProductType` | `ProductType`；`get/listen` | 获取硬件产品类型。 | 只用于展示/诊断型号。`UNKNOWN`、空值或不支持不能自动当作“无飞机”。 |
| `RemoteControllerKey.KeyRemoteControllerType` | `RemoteControllerType`；`get/listen` | 获取遥控器类型。 | 仅当遥控器连接 Key 为 `true` 时展示；`UNKNOWN` / `NONE` 仍是枚举值，不可擅自改成断开。 |

**官方来源**：[ProductKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_Product_ProductKey.html)；[RemoteControllerKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_RemoteController_RemoteControllerKey.html)；[FlightControllerKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_FlightController_FlightControllerKey.html)。

### 对频状态与动作

| API | 类型与能力 | 官方原义 | 当前项目位置 | 使用限制 |
| --- | --- | --- | --- | --- |
| `RemoteControllerKey.KeyPairingStatus` | `PairingState`；`get/listen` | 获取遥控器对频状态。 | `android-pairing-status-adapter/.../MsdkV5PairingStatusApi.kt` | 它是对频过程/结果，不是“当前飞机在线”。保留 MSDK 原枚举名。 |
| `KeyRequestPairing` | `DJIActionKeyInfo<EmptyMsg, EmptyMsg>`；仅 `performAction` | 开始遥控器与飞行器对频；适用于遥控器无法连接飞机或遥控器被更换。 | `android-pairing-command-adapter/.../MsdkV5PairingCommandApi.kt` | 对频是新机/更换遥控器的显式操作，不能当作日常连接刷新。部分遥控器可能需先切换匹配固件。 |
| `KeyStopPairing` | `DJIActionKeyInfo<EmptyMsg, EmptyMsg>`；仅 `performAction` | 停止遥控器对频操作。 | 同上 | 只在对频流程中使用；停止成功不等同于已对频。 |

当前 5.17 二进制的 `PairingState` 包括：`UNPAIRED`、`PAIRING`、`PAIRED`、`STOP_THEN_SWITCH`、`STOP_FW_TYPE_NOT_MATCHED`、`STOP_DEV_MISMATCH`、`STOP_SUB_RC_REJECT`、`STOP_TARGET_TYPE_MISMATCH`、`STOP_RELAY_NOT_SUPPORT_SUB_RC`、`UNKNOWN`。UI 可以翻译，但协议与诊断必须保留原始名字，新增枚举值一律显示未知并安全拒绝新操作。

**官方来源**：[RemoteControllerKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_RemoteController_RemoteControllerKey.html)。

## 飞控与遥测

**项目位置**：`src/modules/telemetry/android-flight-telemetry-adapter/src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt`。该适配器先持续订阅下列 Key，再分别通过 `getValue(key, callback)` 从硬件读取一次初值；每次 Key 回调都产生完整快照，尚未取得的字段如实为未知。初读不得覆盖请求后先到的监听事件。它不轮询，不自行计算 DJI 飞行状态。

### 飞行状态

| Key | 类型、能力 | 官方原义 | 本项目字段 | 禁止的推断 |
| --- | --- | --- | --- | --- |
| `FlightControllerKey.KeyIsFlying` | `Boolean`；`get/listen` | `true` 表示飞行器正在飞行。 | `isFlying` | `false` 不能单独证明电机关闭、航线未执行或可以安全开始另一动作。`null` 是未知。 |
| `FlightControllerKey.KeyAreMotorsOn` | `Boolean`；`get/listen` | `true` 表示电机已启动。 | `motorsOn` | 电机关闭不代表飞控连接、起飞条件或飞行器健康。`null` 不能伪造成关闭。 |
| `FlightControllerKey.KeyFCFlightMode` | `FCFlightMode`；`get/listen` | 当前 5.17 的飞控飞行模式枚举。 | `flightMode` 原枚举名；`UNKNOWN` 归一为未知。 | 飞行模式名称不能单独授权或否决动作，必须和具体 DJI 动作结果及其他事实一起判断。 |

### 电池与智能低电量返航

| Key | 类型、能力 | 官方原义 | 本项目字段 | 限制 |
| --- | --- | --- | --- | --- |
| `BatteryKey.KeyChargeRemainingInPercent`，索引 `ComponentIndexType.LEFT_OR_MAIN` | `Integer`；`get/listen` | 获取该电池的剩余电量百分比。官方说明：多电池总百分比需要按不同 `componentIndex` 获取容量和剩余量后计算。 | `batteryPercent` | 只能接受 `0..100`。它是主/左电池，不是多电池总量；空值不是 0%。 |
| `FlightControllerKey.KeyLowBatteryRTHInfo` | `LowBatteryRTHInfo`；`get/listen` | 获取智能低电量相关信息；仅在低电量返航启用时有效。 | `lowBatteryRthState`、`remainingFlightTimeSeconds` | `remainingFlightTime` 不是通用“可飞剩余时间”，也不能单独进入安全门禁。 |

`LowBatteryRTHState` 的原始成员为 `IDLE`、`COUNTING_DOWN`、`EXECUTED`、`CANCELLED`、`UNKNOWN`。本项目只显示/发布前四个已知状态；`UNKNOWN` 或对象为空一律为未知。`COUNTING_DOWN` 时，官方提供另一动作 Key `KeyLowBatteryRTHConfirm` 用于确认/取消返航，但本项目当前 **没有调用它**。

### 高度与位置

| Key | 类型、能力 | 本项目字段 | 解释与边界 |
| --- | --- | --- | --- |
| `FlightControllerKey.KeyAltitude` | `Double`；`get/listen` | `altitudeMeters` | 这是 SDK 的 `Altitude` Key；不能在 UI 写成未经确认的“海拔高度”。值接近 0 可能是该参考高度的正常表现，不能仅据此判断 GPS、高度计或飞机故障。非有限值为未知。 |
| `FlightControllerKey.KeyAircraftLocation` | `LocationCoordinate2D`；`get/listen` | `latitude`、`longitude` | 只在经纬度同时存在、有限且位于纬度 `[-90, 90]`、经度 `[-180, 180]` 时显示。它是二维坐标，不携带高度。 |

**官方来源**：[FlightControllerKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_FlightController_FlightControllerKey.html)；[BatteryKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_Battery_BatteryKey.html)。

## RTMP 图传与原始相机码流

### RTMP 图传

**官方入口**：`MediaDataCenter.getInstance().liveStreamManager`，类型 `ILiveStreamManager`
**项目位置**：`src/modules/live-stream/android-dji-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/dji/android/MsdkV5LiveStreamApi.kt`
**生产链路**：这是当前生产图传路径。它让 MSDK 向桌面 RTMP 地址推流；桌面接收、HTTP-FLV 转换和浏览器播放不属于 MSDK。

| API | 当前固定调用 | 官方原义 | 不能证明 |
| --- | --- | --- | --- |
| `setCameraIndex(LEFT_OR_MAIN)` | 选择主/左相机。 | 设置需要直播的相机索引。 | 没有证明该相机正在输出有效画面。 |
| `setLiveStreamSettings(LiveStreamSettings)` + `LiveStreamType.RTMP` + `RtmpSettings.url` | 设置桌面 RTMP URL。 | 设置直播设置；RTMP 是一种直播类型。 | 设置成功不等于网络端点已可达。 |
| `setLiveStreamQuality(StreamQuality.HD)` | 当前固定 `HD`。 | 设置直播视频质量类型。 | `HD` 不是产品分辨率、相机焦点或桌面播放清晰度的保证。 |
| `setLiveVideoBitrateMode(MANUAL)` | 当前固定手动码率。 | `AUTO` 由 MSDK 自动设码率；`MANUAL` 后可调用 `setLiveVideoBitrate`。 | 手动值不保证所有型号/网络下实际输出恒定。 |
| `setLiveVideoBitrate(1_802_240)` | 当前约 `220 * 1024 * 8 bit/s`。 | 设定直播码率，单位 **bit/s**。 | 不要把单位误作 byte/s 或 Mbps。 |
| `addLiveStreamStatusListener` | 开始前注册状态监听。 | 添加直播状态监听。 | `LiveStreamStatus.isStreaming` 只能说明 DJI 直播侧状态，不等同于桌面播放器已经播放。 |
| `startStream(callback)` | 启动推流。 | 开始直播。 | 成功回调不等于 RTMP 已被桌面接收、HTTP-FLV 已生成或视频已渲染。 |
| `stopStream(callback)` | 停止推流。 | 停止直播。 | 成功回调不等于桌面所有缓冲帧瞬间消失。 |
| `removeLiveStreamStatusListener` | 关闭/替换监听。 | 移除同一监听器实例。 | 不能传新建的等价 listener。 |

`LiveStreamStatus` 当前一对一映射为：`isStreaming`、`resolution.width/height`、`fps`、`vbps`、`packetLoss`、`packetCacheLen`、`rtt`。其中 `vbps`、`packetLoss`、`packetCacheLen` 和 `rtt` 均保持 DJI 返回的原始状态值；桌面播放器状态必须由桌面媒体链路独立报告。开始成功后监听每次状态变化；若 MSDK 明确返回 `isStreaming=false`，图传立刻进入非活动失败态并同步到桌面。图传启动不使用飞控连接作为前置条件，原因不是绕过安全，而是图传与飞控是独立状态域；具体可否开始由 `live-stream` 模块的能力门禁和 MSDK 回调决定。

**当前生产启动门禁**：这是项目在请求 `startStream` 前的保守组合规则，不把它伪称为 `ILiveStreamManager` 单独声明的机型能力。仅当 `SdkAvailability.READY`、`ProductKey.KeyConnection == true`、`AirLinkKey.KeyConnection == true`，且 `CameraKey.KeyConnection(LEFT_OR_MAIN) == true` 时，`canStreamVideo` 才允许发送 `live-stream.start`。`FlightControllerKey.KeyConnection`、电量、航线和对频不参与该图传门禁。任一 Key 为 `false` 或 `null` 时，项目只报告“当前图传链路未就绪”；它不等于机型永久不支持。门禁放行后，`startStream` callback、`LiveStreamStatus.isStreaming` 和桌面首帧仍是三项独立后续事实。

**官方来源**：[ILiveStreamManager](https://developer.dji.com/api-reference-v5/android-api/Components/IMediaDataCenter/ILiveStreamManager.html)。

### 原始相机码流（封存路径）

**官方入口**：`MediaDataCenter.getInstance().cameraStreamManager`，类型 `ICameraStreamManager`
**项目位置**：`src/modules/live-stream/android-camera-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/camera/android/AndroidCameraStreamApi.kt`

该适配器使用：

```kotlin
addReceiveStreamListener(ComponentIndexType.LEFT_OR_MAIN, listener)
removeReceiveStreamListener(theSameListenerInstance)
```

`ReceiveStreamListener` 提供 `ByteArray data`、`offset`、`length` 和 `StreamInfo`。项目只转发指定切片及其 codec、宽高、帧率、PTS、关键帧标记；`H264`/`H265` 以原始枚举映射，未知类型拒绝处理。它没有调用 `putCameraStreamSurface`、YUV `addFrameListener` 或 `ILiveStreamManager`。

这是封存的 WHIP/WebRTC 原始帧路径，**不是当前生产 RTMP 图传链路**。保留源码用于回溯，禁止在没有独立设计、编译测试和真机验证的情况下恢复命令入口或修改生产 RTMP 行为。

**官方来源**：[ICameraStreamManager](https://developer.dji.com/api-reference-v5/android-api/Components/IMediaDataCenter/ICameraStreamManager.html)。

## DJI Wayline 航线

**官方入口**：`WaypointMissionManager.getInstance()`，类型 `IWaypointMissionManager`
**项目位置**：`src/modules/wayline-mission/android-dji-wayline-adapter/src/main/kotlin/com/skycommand/relay/wayline/android/MsdkV5WaypointMissionApi.kt`

| API | 官方原义 | 项目封装 | 关键约束 |
| --- | --- | --- | --- |
| `init()` | 初始化航线管理器。 | 第一次调用前由 `ensureInitialized()` 执行一次。 | 必须在 MSDK 就绪后使用；不要每个命令重复初始化。 |
| `pushKMZFileToAircraft(path, CompletionCallbackWithProgress<Double>)` | 上传 DJI WPML 定义的 KMZ 航线文件；同名文件覆盖此前文件；可上传多个文件；一个 KMZ 含一个航线任务，可含多条 wayline。 | `upload(path, ...)` 透传进度、成功、失败。 | “上传成功”仅代表文件上传到飞机，不代表自动起飞或开始执行。文件名是后续启动/停止的身份。 |
| `startMission(missionFileName, CompletionCallback)` | 启动指定文件的航线任务。 | `start(name, ...)`。 | 官方特别说明：航线起飞阶段不要调用 `KeyStartGoHome`；若要停止航线，调用 `stopMission`。成功回调仍要等执行状态变化确认。 |
| `pauseMission(callback)` | 暂停航线。 | `pause(...)`。 | 它不是上传/停止的替代。 |
| `resumeMission(callback)` | 继续航线。 | `resume(...)`。 | 必须由任务当前状态和 SDK 回调共同决定可用性。 |
| `stopMission(missionFileName, callback)` | 停止指定文件的航线。 | `stop(name, ...)`。 | 名称必须与上传/目标任务一致。停止请求成功不是 UI 可以立刻写“已完成”。 |
| `addWaypointMissionExecuteStateListener(listener)` | 订阅航线执行状态。 | 注册后映射为项目任务阶段。 | 它是任务运行态证据；移除时必须使用同一 listener。 |
| `destroy()` | 销毁该 manager 的资源。 | `close()` 调用。 | 不能在还有有效使用者时过早销毁。 |

当前 `WaypointMissionExecuteState` 的原始成员包括：`DISCONNECTED`、`IDLE`、`NOT_SUPPORTED`、`READY`、`UPLOADING`、`PREPARING`、`ENTER_WAYLINE`、`EXECUTING`、`INTERRUPTED`、`RECOVERING`、`FINISHED`、`RETURN_TO_START_POINT`、`UNKNOWN`。项目显式映射：

- `PREPARING` / `UPLOADING` / `RECOVERING` -> 准备中；
- `ENTER_WAYLINE` -> 进入航线；
- `EXECUTING` / `RETURN_TO_START_POINT` -> 执行中；
- `INTERRUPTED` -> 已中断；
- `FINISHED` -> 已完成；
- `DISCONNECTED` -> 链路中断；
- `IDLE` / `READY` -> 空闲；
- `NOT_SUPPORTED` / `UNKNOWN` / 新枚举 -> 未知。

该映射是 Sky Command 业务显示，不改变 DJI 枚举原义。新状态必须先保持“未知”再讨论是否映射，不得默认成成功或完成。

**官方来源**：[IWaypointMissionManager](https://developer.dji.com/api-reference-v5/android-api/Components/IWaypointMissionManager/IWaypointMissionManager.html)。

## 直接飞行控制

**项目位置**：`src/modules/flight-control/android-dji-flight-adapter/src/main/kotlin/com/skycommand/relay/flight/dji/android/MsdkV5FlightApi.kt`。所有三个调用都是 `KeyManager.performAction(KeyTools.createKey(...), callback)`；类型为 `DJIActionKeyInfo<EmptyMsg, EmptyMsg>`，只允许执行动作，不能读写订阅。

| 动作 Key | 官方原义 | 当前项目方法 | 不可省略的理解 |
| --- | --- | --- | --- |
| `FlightControllerKey.KeyStartTakeoff` | 开始自动起飞；当飞机悬停在离地约 1.2 m（4 ft）时官方视为起飞完成。电机已开时不可执行。 | `takeoff(...)` | 成功回调不取代 `KeyAreMotorsOn`、`KeyIsFlying` 和飞行模式的后续观察。 |
| `KeyStartAutoLanding` | 开始自动降落。 | `land(...)` | 成功回调不是已经落地；必须观察后续状态。 |
| `KeyStartGoHome` | 开始智能返航；GPS 信号不好时不能启动。返航期间可用遥控器杆量避障，官方支持由 `KeyStopGoHome` 或遥控器退出。 | `returnHome(...)` | 航线起飞阶段不能用它替代 `stopMission`。 |

此适配器本身不做飞行授权判断。起飞/返航/降落的业务门禁在 `flight-control` 与组合根处理，仍必须满足完整 MSDK/设备事实、对应操作的官方约束和人工安全程序。任何未知、断开或操作失败都必须安全拒绝新的开始型命令。

**官方来源**：[FlightControllerKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_FlightController_FlightControllerKey.html)。

## 相机与链路设置

**官方入口**：`KeyManager.getValue` / `setValue`
**项目位置**：`src/modules/device-settings/android-dji-settings-adapter/src/main/kotlin/com/skycommand/relay/settings/dji/android/MsdkV5SettingsApi.kt`

所有相机 Key 用 `ComponentIndexType.LEFT_OR_MAIN` 创建。这里的“读取设置”是用户主动发起的设置命令，当前返回的是同步 MSDK 缓存快照，不属于设备页实时状态、控制门禁或 Android 到桌面的遥测推送；因此它只能表示 MSDK 已缓存的设置值，不能声称已向硬件重新确认。写入按请求顺序串行执行，全部成功后读取该缓存快照；写入成功但读不到完整快照时，项目返回失败，不能把请求参数当设备事实。若未来需要将设置做成实时设备事实，必须另建“持续订阅 + 显式异步硬件读取”的状态模块，不能把此命令响应混入遥测。

### 相机 Key

| Key | 类型与能力 | 官方原义和前置条件 | 项目使用 |
| --- | --- | --- | --- |
| `CameraKey.KeyConnection(LEFT_OR_MAIN)` | `Boolean`；`get/listen` | 所选主/左相机组件连接事实。 | 图传启动门禁的一个原始输入；原样上报 `CONNECTED` / `DISCONNECTED` / `UNKNOWN`，不由飞控或产品 Key 推断。 |
| `CameraKey.KeyAELockEnabled` | `Boolean`；`get/set/listen` | 相机镜头自动曝光锁；当前直播流源的 `CameraExposureMode` 必须为 `PROGRAM`。 | 读写 `autoExposureLockEnabled`。未满足曝光模式前置条件时，失败应如实返回。 |
| `CameraKey.KeyCameraFocusMode` | `CameraFocusMode`；`get/set/listen` | 设置/获取变焦镜头焦点模式。官方要求视频源为 `ZOOM_CAMERA`；创建 Key 时还须选择匹配的镜头类型。单镜头相机/PSDK 单镜头使用 `CAMERA_LENS_DEFAULT`；特定机型支持集合不同。 | 当前项目仅以相机索引创建 Key。改动焦点能力前必须先核对目标 Mini 4 Pro 的镜头/固件支持，不能把“设置成功”当作已经完成对焦。 |

### AirLink Key

| Key | 类型与能力 | 官方原义 | 项目使用与限制 |
| --- | --- | --- | --- |
| `AirLinkKey.KeyConnection` | `Boolean`；`get/listen` | AirLink 组件连接事实。 | 图传启动门禁的一个原始输入；原样上报 `CONNECTED` / `DISCONNECTED` / `UNKNOWN`，不由产品、飞控或相机 Key 推断。 |
| `AirLinkKey.KeyFrequencyBand` | `FrequencyBand`；`get/set/listen` | 设置工作频段。 | 读写频段。频段可写不表示任意值均被当前地区、机型和固件接受。 |
| `KeyChannelSelectionMode` | `ChannelSelectionMode`；`get/set/listen` | 设置自动或手动选频道模式。 | 读写频道选择模式。必须先以读取值确认模式。 |
| `KeyBandwidth` | `Bandwidth`；`get/set/listen` | 设置下行链路带宽；仅在 `KeyChannelSelectionMode == MANUAL` 时可用。 | 读写带宽。不得在自动频道模式下承诺设置生效。 |
| `KeyDynamicDataRate` | `Double`；`get/listen` | 获取飞机到遥控器下行链路动态数据率，单位 **Mbps**。 | 只读展示。它不是 RTMP 实际网络吞吐、码率设置，也不是视频播放器状态。 |

**官方来源**：[CameraKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_Camera_CameraKey.html)；[AirLinkKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_Airlink_AirlinkKey.html)。

## 错误、型号差异和控制边界

### 三类“失败”不能混成一种

| 类别 | 例子 | 正确处理 |
| --- | --- | --- |
| 生命周期失败 | `onRegisterFailure`、MSDK 未就绪 | 禁止所有需要 DJI MSDK 的新操作；保留明确的生命周期状态。 |
| Key 无值/不支持 | `getValue` 为 `null`、`UNKNOWN`、`isKeySupported == false` | 显示未知或不支持；不降级成 `false` / `0` / 空闲。 |
| 操作失败 | `performAction` / `startStream` / `pushKMZFileToAircraft` 的 `onFailure(IDJIError)` | 操作失败；保持已确认的运行状态，等待对应状态监听或人工恢复。 |

### 每个新 Key 的接入规则

1. 先核对目标版本的官方类型、`canGet/canSet/canListen/canPerformAction` 和目标产品支持情况。
2. 明确组件索引、镜头类型和参数单位；不接受“默认就是主电池/主相机”的猜测。
3. 在 Android DJI 适配器中隔离 DJI 类，不让 `dji.*` 类型穿透到业务模块、桌面协议或 UI。
4. 对 `null`、`UNKNOWN`、失败回调、重复回调、关闭后的迟到回调写确定性测试。
5. 写入/动作必须保留 DJI callback；需要运行态结果时再订阅对应状态，而不是仅凭请求成功改变 UI。
6. 涉及飞行、任务、图传的改动必须单独真机验证。无飞机时可做编译与假适配器测试，但不得声称已验证飞行行为。

### MSDK 状态与 Sky Command 状态的边界

MSDK 提供的是各组件/Manager 的原始事实。Sky Command 的“手机中继在线”“桌面播放器正在播放”“任务已暂存”“操作是否允许”等是本项目状态，来源分别是 WebSocket、媒体管线、任务模块和能力门禁。它们不能被单个 DJI Key 取代，也不能反向改写 DJI Key 的含义。

## 当前调用总表与升级检查表

### 生产与封存适配器清单

| 模块 | 文件 | 直接 DJI API |
| --- | --- | --- |
| MSDK 生命周期 | `device-connection/android-dji-sdk-adapter/.../MsdkV5ManagerBridge.kt` | `SDKManager.init/registerApp`、`SDKManagerCallback` |
| 产品/AirLink/主相机/飞控事实 | `device-connection/android-aircraft-adapter/.../MsdkV5AircraftApi.kt` | `ProductKey.KeyConnection`、`AirLinkKey.KeyConnection`、`CameraKey.KeyConnection(LEFT_OR_MAIN)`、`KeyProductType`、`FlightControllerKey.KeyConnection` |
| 遥控器事实 | `device-connection/android-remote-controller-adapter/.../MsdkV5RemoteControllerApi.kt` | `RemoteControllerKey.KeyConnection`、`KeyRemoteControllerType` |
| 对频事实 | `device-connection/android-pairing-status-adapter/.../MsdkV5PairingStatusApi.kt` | `RemoteControllerKey.KeyPairingStatus` |
| 对频动作 | `device-connection/android-pairing-command-adapter/.../MsdkV5PairingCommandApi.kt` | `KeyRequestPairing`、`KeyStopPairing` |
| 飞行遥测 | `telemetry/android-flight-telemetry-adapter/.../MsdkV5FlightTelemetryApi.kt` | 飞行、电机、模式、主电池、低电量返航、高度、位置 Key |
| RTMP 图传 | `live-stream/android-dji-stream-adapter/.../MsdkV5LiveStreamApi.kt` | `MediaDataCenter.liveStreamManager`、`ILiveStreamManager` |
| 原始码流（封存） | `live-stream/android-camera-stream-adapter/.../AndroidCameraStreamApi.kt` | `MediaDataCenter.cameraStreamManager`、`ICameraStreamManager` |
| 航线 | `wayline-mission/android-dji-wayline-adapter/.../MsdkV5WaypointMissionApi.kt` | `WaypointMissionManager` |
| 直接飞行 | `flight-control/android-dji-flight-adapter/.../MsdkV5FlightApi.kt` | 三个 `FlightControllerKey` 动作 Key |
| 设置 | `device-settings/android-dji-settings-adapter/.../MsdkV5SettingsApi.kt` | `CameraKey`、`AirLinkKey` |

### 变更前检查表

- [ ] 修改的是上述唯一 Android DJI 适配器之一，而不是桌面模块、UI 或网络模块中复制 DJI 逻辑。
- [ ] 已确认 Gradle 仍解析 `5.17.0`；若升级版本，已完成 API 差异审计。
- [ ] 已在官方 API Reference 核对方法/Key 的原义、参数单位和约束。
- [ ] 已区分同步缓存读、异步硬件读和订阅状态；没有把缺失值压成业务默认值。
- [ ] 已处理 `null`、`UNKNOWN`、未支持、失败、取消、重复与迟到回调。
- [ ] 已保持图传、控制、飞控、任务、桌面播放器这些状态域独立。
- [ ] 写入或动作后有状态确认路径，不只依赖 `onSuccess`。
- [ ] 涉及航线/飞行的代码没有在无实机条件下被宣称为真机验证成功。

## 官方来源

所有链接均为 DJI 官方域名；访问日期为 2026-08-31。

- [DJI MSDK V5 Android API Reference：SDKManager](https://developer.dji.com/api-reference-v5/android-api/Components/SDKManager/DJISDKManager.html)
- [DJI MSDK V5 Android API Reference：SDKManagerCallback](https://developer.dji.com/api-reference-v5/android-api/Components/SDKManager/ISDKManager_SDKManagerCallback.html)
- [DJI MSDK V5 Android API Reference：IKeyManager](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/IKeyManager.html)
- [DJI MSDK V5 Android API Reference：ProductKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_Product_ProductKey.html)
- [DJI MSDK V5 Android API Reference：RemoteControllerKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_RemoteController_RemoteControllerKey.html)
- [DJI MSDK V5 Android API Reference：FlightControllerKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_FlightController_FlightControllerKey.html)
- [DJI MSDK V5 Android API Reference：BatteryKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_Battery_BatteryKey.html)
- [DJI MSDK V5 Android API Reference：CameraKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_Camera_CameraKey.html)
- [DJI MSDK V5 Android API Reference：AirLinkKey](https://developer.dji.com/api-reference-v5/android-api/Components/IKeyManager/Key_Airlink_AirlinkKey.html)
- [DJI MSDK V5 Android API Reference：CommonCallbacks](https://developer.dji.com/api-reference-v5/android-api/Components/DJICommonCallbacks/DJICommonCallbacks.html)
- [DJI MSDK V5 Android API Reference：ILiveStreamManager](https://developer.dji.com/api-reference-v5/android-api/Components/IMediaDataCenter/ILiveStreamManager.html)
- [DJI MSDK V5 Android API Reference：ICameraStreamManager](https://developer.dji.com/api-reference-v5/android-api/Components/IMediaDataCenter/ICameraStreamManager.html)
- [DJI MSDK V5 Android API Reference：IWaypointMissionManager](https://developer.dji.com/api-reference-v5/android-api/Components/IWaypointMissionManager/IWaypointMissionManager.html)

本手册不摘抄或转述 DJI 飞行安全限制。最终飞行可行性、地区法规、禁飞区、固件限制、遥控器提示和机载安全检查以 DJI 飞行器、DJI Fly / Pilot、固件和当地法规为准。

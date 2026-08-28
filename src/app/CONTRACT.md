# Android Application 组合根契约

状态：已实现
版本：1.0.0
Gradle 路径：`:app`

## 唯一职责

本模块只负责把 `relay-settings`、`app-runtime`、`device-connection`、`telemetry`、`live-stream`、`wayline-mission`、`flight-control`、`device-settings` 和 `relay-gateway` 一级模块与其 Android 适配器组装成可运行应用，并提供保存电脑端中继 WebSocket 地址（默认 `ws://192.168.1.10:8080/relay`，不是 RTMP 图传口）、启动和停止中继、查看关键状态的最小界面。业务规则仍由各一级模块拥有。

## 二级职责

1. `MainActivity`：只承载地址输入、启动/停止操作和状态展示；不直接调用 DJI 或 WebSocket。状态顺序必须是运行时、电脑连接、遥控器、对频、飞机，不得把飞机画在对频前面。展示文字必须是操作员能读的中文，不得把 `ACTIVE`、`RECONNECT_WAIT` 等机器词直接画上。开始/停止对频按钮只反映 `device-connection` 的能力：飞机未连接时允许开始对频，飞机已连接时不允许。首次创建时必须在 `onCreate`、生命周期到达 `STARTED` 前 `attach` 权限适配器，并把该实例交给图。中继仍在启动或运行时，界面销毁必须保留图和适配器，恢复时必须 `rebind` 到新界面，不得再次 `attach`，也不得因此停止电脑会话。仅当中继已停止、失败或正在停止时，`onDestroy` 才关闭图和适配器。
2. `MobileRelayGraph`：只创建一级模块的真实实例、注册命令并集中释放资源；不重新实现模块业务规则，对频按钮条件必须来自 `DeviceConnection.capabilities()`。生产图传只装配 RTMP `LiveStream` 的 DJI 适配器与命令处理器。归档的 WHIP/WebRTC 源码及其独立测试可保留，但组合根不得创建它、注册其命令、订阅其状态或把其依赖打入 APK。权限适配器由 Activity 持有，图关闭时不得关闭它。
3. `RelayBootstrapModule`：只执行设备、遥测和网关的有序启停，隔离启动代次。SDK 离开 `READY` 时通知直播、航线、飞行控制和设备设置失效。网关已是 `ACTIVE` 但遥测尚未启动时发布设备链路快照。网关从 `ACTIVE` 进入重连或停止时只通知生产 RTMP 直播失效，不得因此停止网关重连。`MobileRelayGraph` 装配网关时握手超时必须为 15 秒，与桌面端一致。
4. `CompositeTelemetrySource`：只原子读取设备、飞行、直播和航线四类快照，并把任一来源变化合并为统一通知。
5. `TelemetryFrameMapper`：只把完整业务快照无损映射到协议 JSON；缺失值保持为 `null`。`pairing.status` 的结构化 `result` 也只由该映射器从当前遥测快照生成。

上述职责之间只通过稳定接口协作。界面不得持有模块内部端口，映射器不得访问 Android，生命周期模块不得解析命令，组合源不得发布网络消息。

## 启动与停止

Android 清单必须完整声明权限策略会请求的运行时权限：定位、电话状态、录音、通知，以及 API 32 及以下的外部存储读取权限；适配器会过滤未声明的权限，故缺失声明属于阻止启动的构建契约违例。USB 使用 Android accessory 模式：`android.hardware.usb.accessory` 必须为必需能力，`android.hardware.usb.host` 只能标为可选能力。本程序仅在用户已进入应用后由权限模块请求 USB 授权，不负责 USB 插入时自动启动界面。

Android 进程必须使用 `android-dji-sdk-adapter` 提供的 `DjiSdkApplication`。它在任何 DJI API 被访问前安装 DJI 运行时；应用组合根不得自行初始化 DJI，也不得替换为承载业务状态的 Application。

中继地址允许 `ws://` 与 `wss://`。因此 Android 清单必须允许明文网络流量，确保用户配置的局域网 `ws://` 电脑端能够实际连接；地址中若含认证信息，任何状态文本和诊断事件都不得记录其完整内容。部署具备 TLS 的电脑端时应配置 `wss://`。

1. 用户保存合法 `ws://` 或 `wss://` 地址后才能启动。
2. `AppRuntime` 先取得运行时权限，再启动前台服务，最后调用组合启动模块。电脑 WebSocket 不得等待 USB。中继进入 `RUNNING` 后，组合根单独请求 `USB_ACCESS`：没有附件时保持等待，接入后弹出系统授权；授权成功、USB 再次接入且已授权，或 SDK 首次进入 `READY` 时，都必须重启遥控器和飞机观察。USB 拒绝或失败后必须允许再次请求，不得把 USB 授权绑进 `AppRuntime.start()`。USB 广播接收器必须存活到权限适配器关闭，不能随 Activity 进入后台而注销。
3. 组合启动模块先启动 `device-connection`，随即启动 `relay-gateway`。电脑 WebSocket 不得等待 DJI SDK。仅在 DJI 状态为 `READY` 后启动飞行遥测源和 `telemetry`。SDK 离开 `READY` 时必须停止遥测并通知直播/航线/飞行控制/设备设置失效，但不得因此断开电脑连接；SDK 再次 `READY` 后只重试遥测。网关离开 `ACTIVE` 时必须通知生产 RTMP 直播失效，操作者重连后必须重新启动图传。
4. 网关已是 `ACTIVE` 且遥测已启动时调用 `telemetry.publishCurrent()`，保证电脑端立即收到完整首帧。网关已是 `ACTIVE` 但 SDK 尚未 `READY` 时，必须发布当前设备链路快照（SDK、遥控器、飞机、对频），不得等待飞行遥测模块启动；`ACTIVE` 期间设备事实变化必须再次发布该链路快照。网关先于 SDK 就绪进入 `ACTIVE` 时，遥测启动后再补发完整首帧。
5. 停止顺序严格反向：网关、遥测、飞行源、设备；同时关闭航线缓存、DJI 航线适配器、前台服务端口和线程资源。权限适配器不随中继图重建关闭。中继仍在运行时，适配器不得随界面销毁关闭；USB 广播接收器必须注册在应用上下文，存活到适配器关闭。
6. 设备离线时必须通知直播、航线、飞行控制和设备设置模块失效；旧 DJI、网络和状态回调不得恢复已停止代次。
7. 任一启动步骤抛出异常时，必须注销已建立的监听、逆序停止已启动资源，并允许后续完整重试。
8. 任何可安装 APK 都必须在打包时通过 Gradle 属性 `DJI_API_KEY` 注入已注册的 DJI API Key；未提供时打包必须失败，不能生成携带空密钥的伪可用 APK。该密钥不得进入源码、契约或版本控制。
9. 组合启动、异步进入 SDK 就绪后的遥测/网关启动、监听注销及各停止步骤发生异常时，`RelayBootstrapModule` 必须产生稳定的生命周期诊断事件。诊断只包含事件码与固定安全说明；记录失败不能改变回滚、停止或后续重试语义。

## 命令与数据

组合根注册 `telemetry.read`、`pairing.start`、`pairing.stop`、`pairing.status`、`live-stream.start|stop`、全部 `wayline.*` 命令、`flight.takeoff`、`flight.land`、`flight.return-home`、`device.settings.camera.read`、`device.settings.camera.write`、`device.settings.transmission.read` 和 `device.settings.transmission.write`。遥测快照必须完整映射为协议 `TelemetryFrame`，可选值缺失时使用 JSON null，不伪造零值。任何结果不得泄露密钥、路径或原始异常。命令处理器在组合根外包一层诊断记录：成功或拒绝写入现有 `DiagnosticJournal`，`telemetry.read` 成功不记以免刷屏。会话状态变化和结束原因（含握手超时）同样写入该 journal，详情只用固定安全说明。

`telemetry.read` 主动发布当前完整快照；配对命令使用 30 秒超时并且每条命令恰好产生一个终态。`pairing.status` 成功时必须通过 `command-result.result` 返回根契约 §7.4 的结构化快照：`pairingState`、`aircraftConnected`、`flightControllerConnected`、`aircraftModel`、`motorsOn`、`sdkRegistered`。该结果只来自当前遥测快照；遥测不可用时拒绝命令。`pairing.start` / `pairing.stop` 不得附带该结构化 `result`。生产 RTMP 直播只复用 `LiveStream` 门面提供的处理器。航线、飞行控制和设备设置命令直接复用各一级模块门面提供的处理器。飞行控制处理器要求每次命令都带 `confirm: true`；设备设置处理器在读写成功时通过 `command-result.result` 返回完整结构化快照。四类 DJI 业务处理器都只能经 `device-connection` 的共享操作协调器调用 DJI，不得由组合根另建执行路径。

## 验证

必须通过组合逻辑单元测试、全仓 JVM 测试、所有 Android 适配器 Debug 编译和 `:app:assembleDebug`。生成 APK 只能证明编译和打包；DJI API Key、遥控器、飞行器、RTMP 服务和电脑端服务仍需真机联调。

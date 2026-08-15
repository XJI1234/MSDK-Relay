# Android Application 组合根契约

状态：已实现
版本：1.0.0
Gradle 路径：`:app`

## 唯一职责

本模块只负责把 `relay-settings`、`app-runtime`、`device-connection`、`telemetry`、`live-stream`、`wayline-mission`、`flight-control`、`device-settings` 和 `relay-gateway` 九个一级模块与其 Android 适配器组装成可运行应用，并提供保存电脑端中继 WebSocket 地址（默认 `ws://192.168.1.10:8080/relay`，不是 RTMP 图传口）、启动和停止中继、查看关键状态的最小界面。业务规则仍由各一级模块拥有。

## 二级职责

1. `MainActivity`：只承载地址输入、启动/停止操作和状态展示；不直接调用 DJI 或 WebSocket。它必须在 `onCreate`、生命周期到达 `STARTED` 前 `attach` 权限适配器，并把该实例交给图；`onDestroy` 才关闭适配器。图重建不得再次 `attach`。
2. `MobileRelayGraph`：只创建九个一级模块的真实实例、注册命令并集中释放资源；不重新实现模块业务规则。权限适配器由 Activity 持有，图关闭时不得关闭它。
3. `RelayBootstrapModule`：只执行设备、遥测和网关的有序启停，隔离启动代次，并在设备失效时通知直播、航线、飞行控制和设备设置模块。
4. `CompositeTelemetrySource`：只原子读取设备、飞行、直播和航线四类快照，并把任一来源变化合并为统一通知。
5. `TelemetryFrameMapper`：只把完整业务快照无损映射到协议 JSON；缺失值保持为 `null`。`pairing.status` 的结构化 `result` 也只由该映射器从当前遥测快照生成。

上述职责之间只通过稳定接口协作。界面不得持有模块内部端口，映射器不得访问 Android，生命周期模块不得解析命令，组合源不得发布网络消息。

## 启动与停止

Android 清单必须完整声明权限策略会请求的运行时权限：定位、电话状态、录音、通知，以及 API 32 及以下的外部存储读取权限；适配器会过滤未声明的权限，故缺失声明属于阻止启动的构建契约违例。USB 使用 Android accessory 模式：`android.hardware.usb.accessory` 必须为必需能力，`android.hardware.usb.host` 只能标为可选能力。本程序仅在用户已进入应用后由权限模块请求 USB 授权，不负责 USB 插入时自动启动界面。

Android 进程必须使用 `android-dji-sdk-adapter` 提供的 `DjiSdkApplication`。它在任何 DJI API 被访问前安装 DJI 运行时；应用组合根不得自行初始化 DJI，也不得替换为承载业务状态的 Application。

中继地址允许 `ws://` 与 `wss://`。因此 Android 清单必须允许明文网络流量，确保用户配置的局域网 `ws://` 电脑端能够实际连接；地址中若含认证信息，任何状态文本和诊断事件都不得记录其完整内容。部署具备 TLS 的电脑端时应配置 `wss://`。

1. 用户保存合法 `ws://` 或 `wss://` 地址后才能启动。
2. `AppRuntime` 先取得运行时和 USB 权限，再启动前台服务，最后调用组合启动模块。
3. 组合启动模块先启动 `device-connection`；仅在 DJI 状态为 `READY` 后启动飞行遥测源、`telemetry` 和 `relay-gateway`。SDK 离开 `READY` 时必须停止网关和遥测，允许后续再次就绪后完整重试。
4. 网关进入 `ACTIVE` 时调用 `telemetry.publishCurrent()`，保证电脑端立即收到完整首帧。
5. 停止顺序严格反向：网关、遥测、飞行源、设备；同时关闭航线缓存、DJI 航线适配器、前台服务端口和线程资源。权限适配器随 Activity 销毁关闭，不随中继图重建关闭。
6. 设备离线时必须通知直播、航线、飞行控制和设备设置模块失效；旧 DJI、网络和状态回调不得恢复已停止代次。
7. 任一启动步骤抛出异常时，必须注销已建立的监听、逆序停止已启动资源，并允许后续完整重试。
8. 任何可安装 APK 都必须在打包时通过 Gradle 属性 `DJI_API_KEY` 注入已注册的 DJI API Key；未提供时打包必须失败，不能生成携带空密钥的伪可用 APK。该密钥不得进入源码、契约或版本控制。
9. 组合启动、异步进入 SDK 就绪后的遥测/网关启动、监听注销及各停止步骤发生异常时，`RelayBootstrapModule` 必须产生稳定的生命周期诊断事件。诊断只包含事件码与固定安全说明；记录失败不能改变回滚、停止或后续重试语义。

## 命令与数据

组合根注册 `telemetry.read`、`pairing.start`、`pairing.stop`、`pairing.status`、`live-stream.start|stop`、全部 `wayline.*` 命令、`flight.takeoff`、`flight.land`、`flight.return-home`、`device.settings.camera.read`、`device.settings.camera.write`、`device.settings.transmission.read` 和 `device.settings.transmission.write`。遥测快照必须完整映射为协议 `TelemetryFrame`，可选值缺失时使用 JSON null，不伪造零值。任何结果不得泄露密钥、路径或原始异常。命令处理器在组合根外包一层诊断记录：成功或拒绝写入现有 `DiagnosticJournal`，`telemetry.read` 成功不记以免刷屏。会话状态变化和结束原因（含握手超时）同样写入该 journal，详情只用固定安全说明。

`telemetry.read` 主动发布当前完整快照；配对命令使用 30 秒超时并且每条命令恰好产生一个终态。`pairing.status` 成功时必须通过 `command-result.result` 返回根契约 §7.4 的结构化快照：`pairingState`、`aircraftConnected`、`flightControllerConnected`、`aircraftModel`、`motorsOn`、`sdkRegistered`。该结果只来自当前遥测快照；遥测不可用时拒绝命令。`pairing.start` / `pairing.stop` 不得附带该结构化 `result`。直播、航线、飞行控制和设备设置命令直接复用各一级模块门面提供的处理器。飞行控制处理器要求每次命令都带 `confirm: true`；设备设置处理器在读写成功时通过 `command-result.result` 返回完整结构化快照。四类 DJI 业务处理器都只能经 `device-connection` 的共享操作协调器调用 DJI，不得由组合根另建执行路径。

## 验证

必须通过组合逻辑单元测试、全仓 JVM 测试、所有 Android 适配器 Debug 编译和 `:app:assembleDebug`。生成 APK 只能证明编译和打包；DJI API Key、遥控器、飞行器、RTMP 服务和电脑端服务仍需真机联调。

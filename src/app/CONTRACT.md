# Android Application 组合根契约

状态：已实现
版本：1.0.0
Gradle 路径：`:app`

## 唯一职责

本模块只负责把 `relay-settings`、`app-runtime`、`device-connection`、`telemetry`、`live-stream`、`wayline-mission` 和 `relay-gateway` 七个一级模块与其 Android 适配器组装成可运行应用，并提供保存电脑端 WebSocket 地址、启动和停止中继、查看关键状态的最小界面。业务规则仍由各一级模块拥有。

## 二级职责

1. `MainActivity`：只承载地址输入、启动/停止操作和状态展示；不直接调用 DJI 或 WebSocket。
2. `MobileRelayGraph`：只创建七个一级模块的真实实例、注册命令并集中释放资源；不重新实现模块业务规则。
3. `RelayBootstrapModule`：只执行设备、遥测和网关的有序启停，隔离启动代次，并在设备失效时通知直播和航线。
4. `CompositeTelemetrySource`：只原子读取设备、飞行、直播和航线四类快照，并把任一来源变化合并为统一通知。
5. `TelemetryFrameMapper`：只把完整业务快照无损映射到协议 JSON；缺失值保持为 `null`。

上述职责之间只通过稳定接口协作。界面不得持有模块内部端口，映射器不得访问 Android，生命周期模块不得解析命令，组合源不得发布网络消息。

## 启动与停止

1. 用户保存合法 `ws://` 或 `wss://` 地址后才能启动。
2. `AppRuntime` 先取得运行时和 USB 权限，再启动前台服务，最后调用组合启动模块。
3. 组合启动模块先启动 `device-connection`；仅在 DJI 状态为 `READY` 后启动飞行遥测源、`telemetry` 和 `relay-gateway`。
4. 网关进入 `ACTIVE` 时调用 `telemetry.publishCurrent()`，保证电脑端立即收到完整首帧。
5. 停止顺序严格反向：网关、遥测、飞行源、设备；同时关闭航线缓存、DJI 航线适配器、权限适配器、前台服务端口和线程资源。
6. 设备离线时必须通知直播和航线模块失效；旧 DJI、网络和状态回调不得恢复已停止代次。
7. 任一启动步骤抛出异常时，必须注销已建立的监听、逆序停止已启动资源，并允许后续完整重试。

## 命令与数据

组合根注册 `telemetry.read`、`pairing.start`、`pairing.stop`、`pairing.status`、`live-stream.start|stop` 和全部 `wayline.*` 命令。遥测快照必须完整映射为协议 `TelemetryFrame`，可选值缺失时使用 JSON null，不伪造零值。任何结果不得泄露密钥、路径或原始异常。

`telemetry.read` 主动发布当前完整快照；配对命令使用 30 秒超时并且每条命令恰好产生一个终态。直播和航线命令直接复用各一级模块门面提供的处理器。

## 验证

必须通过组合逻辑单元测试、全仓 JVM 测试、所有 Android 适配器 Debug 编译和 `:app:assembleDebug`。生成 APK 只能证明编译和打包；DJI API Key、遥控器、飞行器、RTMP 服务和电脑端服务仍需真机联调。

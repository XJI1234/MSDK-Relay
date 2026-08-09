# relay-gateway 一级模块契约

状态：待审阅
版本：0.1.0
所属程序：MSDK Relay Android
模块标识：`relay-gateway`

## 1. 模块目的

`relay-gateway` 负责手机与 Sky Command 电脑端之间的唯一 WebSocket 会话。它负责连接生命周期、握手、协议帧收发、命令分发和结果发布，但不负责任何 DJI 业务。

调用方不需要了解 OkHttp、WebSocket、JSON 库、线程模型或连接内部状态。

## 2. 负责与不负责

负责：

- 使用 `relay-settings` 提供的端点和设备身份建立会话；
- 发送 `hello` 并验证 `paired`；
- 接收电脑命令并交给已注册的处理器；
- 发布遥测、命令结果和航线传输结果；
- 识别断线、替换连接、无效帧和协议错误；
- 在会话结束时取消未完成的传输和命令等待。

不负责：

- 调用 DJI SDK；
- 读取或聚合 DJI 状态；
- 生成、校验或上传 KMZ；
- 启动或停止直播；
- 显示 Android UI；
- 解释飞行、直播或航线业务规则。

## 3. 外部接口

```text
RelayGateway.create(config, transport, clock) -> RelayGateway

RelayGateway.start() -> void
RelayGateway.stop() -> void
RelayGateway.connectionState() -> ConnectionState

RelayGateway.registerCommandHandler(name, handler) -> Registration
RelayGateway.publishTelemetry(snapshot) -> PublishResult
RelayGateway.publishCommandResult(result) -> PublishResult
RelayGateway.publishMissionResult(result) -> PublishResult

RelayGateway.onStateChanged(listener) -> Registration
```

`transport` 和 `clock` 是内部 seam 的依赖注入点。生产环境使用 WebSocket adapter，测试使用内存 adapter。它们不得出现在业务模块的公开契约中。

## 4. 连接状态

```text
STOPPED
CONNECTING
AWAITING_PAIRING
ACTIVE
RECONNECT_WAIT
```

规则：

- `start` 只能从 `STOPPED` 或 `RECONNECT_WAIT` 发起连接；
- WebSocket 建立后立即发送一次 `hello`，进入 `AWAITING_PAIRING`；
- 收到合法 `paired` 后进入 `ACTIVE`；
- 只有 `ACTIVE` 允许接收命令和发布数据；
- `stop` 必须关闭连接、取消等待、删除会话临时状态并进入 `STOPPED`；
- 断线不得伪装成正常完成；
- 同一 `deviceId` 的新会话生效后，旧会话必须失效；
- 未经 `paired` 的连接不得执行任何业务命令。

## 5. 命令处理

当前允许注册的业务命令名称为：

```text
telemetry.read
pairing.start
pairing.stop
pairing.status
wayline.generate
wayline.upload
wayline.start
wayline.pause
wayline.resume
wayline.stop
live-stream.start
live-stream.stop
```

下列命令不属于本阶段，必须被稳定拒绝：

```text
flight.takeoff
flight.land
flight.return-home
virtual-stick.*
```

命令处理器必须返回成功结果或稳定错误结果。未注册命令不得导致 WebSocket 断开。每个命令必须使用电脑提供的 `id` 关联结果，处理器异常不得泄漏堆栈、文件路径、原始 KMZ、令牌或 DJI 私有对象。

## 6. 并发与顺序

- 同一连接的协议状态更新必须按接收顺序处理；
- 业务命令可以移交给业务模块自己的执行器，但结果必须带回原始命令 ID；
- gateway 不保证不同命令之间的业务顺序；需要串行执行的模块必须自行声明并实现；
- 同一个任务传输 ID 不允许存在两个活动传输；
- 旧会话的异步结果不得发布到新会话。

## 7. 错误分类

```text
NOT_CONNECTED
HANDSHAKE_TIMEOUT
INVALID_FRAME
UNSUPPORTED_FRAME
INVALID_COMMAND
COMMAND_TIMEOUT
COMMAND_REJECTED
TRANSFER_NOT_ACTIVE
TRANSFER_SUPERSEDED
TRANSFER_FAILED
PROTOCOL_VERSION_UNSUPPORTED
```

错误对象至少包含 `code` 和适合用户显示的短消息；详细信息只能是受限、可脱敏的结构化字段。

## 8. 不变量

- 任意时刻最多一个当前桌面会话；
- 未处于 `ACTIVE` 时不执行命令；
- 断线后不保留可继续执行的未确认命令；
- gateway 不持久化遥测和任务文件；
- gateway 不改变业务模块返回的任务文件内容和 SHA-256；
- 公开结果不能暴露 Android 私有路径或 DJI SDK 类型。

# relay-gateway 一级模块契约

状态：已修订，待审阅
版本：0.2.0
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

外部接口的共同规则：

- `start()` 只启动连接流程，不代表 WebSocket 已连接，也不代表 DJI 设备已连接；
- 只有进入 `ACTIVE` 后，命令和业务发布接口才允许生效；
- `stop()` 返回后，不得再发布旧会话的异步结果；
- `registerCommandHandler()` 只注册命令入口，不授予处理器访问 transport 或会话代次的权限；
- `publishTelemetry()`、`publishCommandResult()` 和 `publishMissionResult()` 只负责把调用方已经构造好的结果发送出去，不生成业务内容。

## 3.1 二级模块划分

`relay-gateway` 只负责电脑通信。为了保持接口小、实现深，拆成以下二级模块：

| 二级模块 | 唯一职责 | 输入 | 输出 | 不得负责 |
| --- | --- | --- | --- | --- |
| `protocol-core` | 定义、编码、解码和校验协议帧 | 原始字节或帧对象 | `Decoded`、`Rejected`、`Ignored` 或编码结果 | 网络、Android、DJI、文件 |
| `transport-adapter` | 适配一次网络连接 | 连接地址、发送字节、网络回调 | 连接打开、收到字节、关闭、发送失败 | 握手、命令和业务状态 |
| `connection-session` | 管理一个会话的唯一代次、握手和生命周期 | transport 事件、设备身份 | `STOPPED`、`CONNECTING`、`AWAITING_PAIRING`、`ACTIVE`、`RECONNECT_WAIT` | 命令业务、遥测内容、发送队列 |
| `command-dispatcher` | 处理命令名到处理器的映射和结果关联 | `CommandFrame`、注册表 | `CommandResultFrame` | DJI 操作、线程创建、网络细节 |
| `mission-transfer` | 管理任务帧顺序、大小、摘要和传输取消 | `mission-begin/chunk/complete` | 完整任务字节或失败结果 | WPMZ 业务校验、DJI 上传 |
| `outbound-publisher` | 管理所有发送帧的顺序，并验证调用方提供的会话代次 | 已构造的协议帧、会话代次 | 发送结果 | 生成遥测和业务结果、创建或修改会话代次 |

每个二级模块都必须有自己的 `CONTRACT.md`。`protocol-core` 的现有契约见 [`protocol-core/CONTRACT.md`](protocol-core/CONTRACT.md)。如果实现目录暂时与 Gradle 模块目录不同，必须在模块迁移记录中注明，不能让同一个模块出现两份互相矛盾的接口说明。

当前仓库的过渡状态是：`protocol-core` 的实现暂时位于仓库根目录的 `protocol-core/` Gradle 模块，而它的二级模块契约位于 `relay-gateway/protocol-core/CONTRACT.md`。这不是两个模块。实现 gateway 之前必须统一目录，或补充明确的迁移记录；后续 agent 不得在两个位置各自创建一套协议实现。

### 二级模块协作顺序

```text
transport-adapter
  -> connection-session
  -> protocol-core
  -> command-dispatcher / mission-transfer
  -> outbound-publisher
```

实际含义：

- transport 只提供字节，不理解 JSON；
- session 先确认当前连接和握手状态；
- protocol-core 负责把字节变成合法帧；
- command-dispatcher 只接收手机端允许的 `command` 帧；
- mission-transfer 只接收手机端允许的三种任务传输帧；
- outbound-publisher 只发送当前会话产生的帧，旧会话的异步结果必须被丢弃。

### 二级模块边界规则

- `connection-session` 不得把原始 `ByteArray` 交给业务模块；必须先经过 `protocol-core`。
- `command-dispatcher` 不得直接解析 DJI 参数；业务模块处理器负责各自命令的字段校验。
- `mission-transfer` 只负责传输完整性。它通过 `MissionSink` 把已校验的内容交给 `wayline-mission`，不得创建 DJI 航线任务。
- `outbound-publisher` 不得允许不同线程直接调用 transport；所有发送必须经过同一个顺序出口。
- `connection-session` 是会话代次的唯一创建者和失效者；`outbound-publisher` 只能验证代次，不得生成或修改代次。
- 断线时必须依次停止接收、取消命令等待、取消任务传输、清空发送队列，最后通知状态监听器。
- 任何异步回调都必须携带会话代次；代次不匹配时只能丢弃，不能发布结果。

### 二级模块之间的交接数据

`mission-transfer` 完成传输校验后，只能交给 `wayline-mission` 一个不包含手机绝对路径的暂存结果：

```text
StagedMission
  transferId
  fileName
  size
  sha256
  readableByMissionModule
```

其中 `readableByMissionModule` 是抽象的可读取句柄或接口，不是 `String` 路径。`mission-transfer` 不得把临时文件名交给电脑端，`mission-state-store` 也不得复制文件字节。暂存结果只有在文件已经完整、摘要匹配、临时文件已原子替换后才能产生。

### 二级模块的最小接口

以下是职责边界，不是要求使用这些具体 Kotlin 名称：

```text
Transport
  connect(endpoint)
  send(bytes)
  close(reason)
  onOpened / onBytes / onClosed / onFailure

CommandRegistry
  register(commandName, handler)
  unregister(commandName)

CommandHandler
  handle(command) -> success(detail) | failure(error)

MissionSink
  begin(metadata)
  append(bytes)
  complete() -> stagedMission
  abort(reason)

OutboundPublisher
  publish(frame) -> PublishResult
```

这些接口的共同要求是：不暴露 OkHttp、Android、DJI、文件绝对路径或线程池类型。测试时可以用内存 transport、记录型 publisher 和临时目录 sink 替换真实适配器。

## 3.2 实现顺序

实现必须按以下顺序推进：

1. `protocol-core`：先完成协议模型、限制和纯 JVM 测试。
2. `connection-session`：用内存 transport 验证握手、重连、旧会话失效和断线清理。
3. `outbound-publisher`：验证发送顺序、发送失败和旧代次结果丢弃。
4. `command-dispatcher`：验证注册、未知命令、异常转换和命令 ID 关联。
5. `mission-transfer`：验证分块、摘要、替换、取消和 `MissionSink` 交接。
6. `transport-adapter`：最后接入真实 WebSocket 库；不得把网络库反向带入前五个二级模块。

每完成一个二级模块，都必须先通过该模块契约中的测试，再连接到下一个模块。

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

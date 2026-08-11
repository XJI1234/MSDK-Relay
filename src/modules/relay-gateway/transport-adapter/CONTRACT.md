# relay-gateway.transport-adapter 模块契约

状态：已批准并已实现
版本：1.0.0
所属一级模块：`relay-gateway`
Gradle 路径：`:relay-gateway:transport-adapter`

本文件是本模块唯一的契约、使用说明、对外接口说明、行为规范和验收依据；实现不得新增改变以下规则的第二份设计文档。

## 1. 目的与唯一职责

`transport-adapter` 将唯一一种 WebSocket 库适配为 `connection-session` 的传输接口：建立 WebSocket、写入原始字节、请求关闭，并把网络回调转换为携带调用方 `SessionGeneration` 的 `opened`、`bytes`、`closed`、`failure` 回调。

它是 `relay-gateway` 唯一允许导入 OkHttp 或具体 WebSocket 类型的二级模块；其他 gateway 模块只能使用 `connection-session` 的 `TransportConnector`、`TransportConnection`、`TransportWriter`、`TransportListener`。

本模块负责建立出站 `ws://`/`wss://`、将每条连接和回调绑定调用方 generation、暴露字节写入器、按接收顺序转发二进制载荷、把库失败/拒绝写入/拒绝关闭/畸形端点转换为稳定传输结果、确保重复网络终态最多触发一次监听器终态回调、使用 OkHttp 15 秒协议 ping 检测停滞电脑会话，并隔离库异常和原始网络细节。

它不处理 JSON、Base64、中继帧、`hello`、`paired` 或协议校验；不持有会话状态、握手超时、重连、命令分发、遥测、任务传输或出站队列；不处理 Android 生命周期、权限、DJI、任务文件或 UI；不决定端点是否持久化/可编辑；不把 WebSocket 文本消息解释为中继消息。

## 2. 对外接口

```text
OkHttpTransportConnector() -> TransportConnector

open(endpoint, generation, listener) -> OpenAccepted(connection) | OpenRejected(safeReason)
connection.generation -> supplied generation
connection.writer.write(bytes) -> WriteAccepted | WriteRejected
connection.enableCallbacks()
connection.close(reason) -> CloseRequested | AlreadyClosed
```

应用组合根创建适配器并交给 `ConnectionSession`；业务模块不得创建、保留或调用 OkHttp `WebSocket`。

## 3. 连接、写入与关闭规则

1. `open` 不得向调用方抛出 WebSocket、URI 或 OkHttp 异常，只接受语法正确的 `ws://`/`wss://`，其他输入以固定安全原因拒绝。
2. 每次成功 `open` 返回拥有完全相同传入 generation 的独立 `TransportConnection`；`open` 返回 `OpenAccepted` 前不得调用 `TransportListener`，同步网络回调必须缓冲。
3. `connection-session` 在拥有连接后恰好调用一次 `enableCallbacks()`；之后按序投递缓冲回调。`onOpen` 只调用一次 `onOpened(connection)`；二进制消息以复制后的字节和自身 generation 按库回调顺序调用 `onBytes`；文本消息必须丢弃；`onClosing` 只请求正常关闭；`onClosed` 与 `onFailure` 中先到者产生唯一匹配终态，后者丢弃。
4. 适配器不比较 generation、不判定过期、不关闭新连接；该策略属于 `connection-session`。收到字节交付前复制，回调返回后不保留。
5. `write` 仅在连接已打开且未终态时发送二进制消息，且先复制调用方字节；库拒绝/异常、打开前/终态后写入返回 `WriteRejected` 不抛出。不重排也不排队，顺序属于 `outbound-publisher`。
6. `close` 幂等：第一次用固定非敏感原因请求正常关闭并返回 `CloseRequested`，之后 `AlreadyClosed`。库拒绝/异常仍使适配器视为关闭且不抛出；显式关闭不得同步回调监听器，网络关闭回调才是终态通知路径。

## 4. 错误、隐私、测试与变更

`OpenRejected` 仅可暴露 `Transport endpoint is invalid`、`Transport connection could not be opened`。公开结果、回调原因、异常不得暴露完整端点、查询、HTTP 响应、原始载荷、关闭原因、令牌、设备 ID、异常消息或堆栈。库回调/内部引擎抛出时必须隔离，打开后最多一次 `onFailure(generation, "Transport failed")`，且不得越过库回调边界。

生产实现可有仅本模块的内部 WebSocket 引擎接缝供内存测试，不得出现在其他模块契约中。生产引擎为 OkHttp 4.12.0，ping 间隔 15 秒；替换库或内部调度只有在全部公开行为不变时允许。

测试必须覆盖有效/无效端点、精确 generation 与独立连接、打开/二进制复制/文本丢弃/回调顺序、重复终态、打开前/正常/拒绝/异常/终态后写入、幂等关闭、回调与监听器异常、同步 open 回调的 enableCallbacks 缓冲、真实本地 OkHttp WebSocket、旧回调 generation、写入/关闭/终态并发，以及架构扫描确认本模块是唯一 OkHttp 导入且无协议、DJI、Android、命令、遥测、任务依赖。改变连接器行为、载荷类型、回调顺序、关闭语义、ping 间隔或允许 scheme 前，必须先更新契约与测试。

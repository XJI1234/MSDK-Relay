# relay-gateway.outbound-publisher 二级模块契约

状态：已批准并实现
版本：0.1.0
父模块：[`../CONTRACT.md`](../CONTRACT.md)
协议依赖：[`../protocol-core/CONTRACT.md`](../protocol-core/CONTRACT.md)
会话依赖：[`../connection-session/CONTRACT.md`](../connection-session/CONTRACT.md)
模块标识：`relay-outbound-publisher`
模块目录：`src/modules/relay-gateway/outbound-publisher/`
Gradle 路径：`:relay-gateway:outbound-publisher`

本文是 `outbound-publisher` 唯一有效的设计、使用说明和验收依据。实现、测试和依赖只能遵守本文，不得通过调用顺序、线程行为或第三方库隐式扩展能力。

## 1. 唯一职责

`outbound-publisher` 只负责一件事：**把已经构造好的、允许由手机发往电脑的协议帧，按一个确定顺序交给当前会话的 writer。**

它不生成业务内容，不创建或失效会话代次，也不拥有连接生命周期。它使用 `connection-session` 创建的 `SessionGeneration` 识别当前 writer；代次不匹配时稳定丢弃结果，绝不把旧操作的输出写进新连接。

本模块是纯 Kotlin/JVM 模块。没有 Android、DJI SDK、真实 WebSocket、文件系统、数据库或网络时，全部行为必须可测试。

## 2. 负责与不负责

### 2.1 负责

- 保存至多一个已附着的 `SessionGeneration` 与其抽象 `TransportWriter`；
- 通过 `protocol-core` 验证并编码待发送帧；
- 串行化所有对当前 writer 的写入；
- 为 `connection-session` 提供 `attach`、`sendHandshake` 和 `discard`；
- 只允许 `hello` 走握手专用接口；
- 只允许遥测、命令结果和任务结果走活动会话发布接口；
- 在编码失败、writer 拒绝、会话过期或方向不允许时返回固定的结构化结果；
- 在 `discard` 返回后保证该代次不再发生新的写入。

### 2.2 明确不负责

- 不打开、关闭、重连或观察 transport；
- 不创建、修改、恢复或比较 `sessionId`，不判断会话是否 `ACTIVE`；
- 不创建、修改或伪造 `SessionGeneration`；
- 不生成遥测、命令结果、任务结果、错误详情或业务帧字段；
- 不解析入站 JSON，不接收命令，不处理任务分块；
- 不保存未送达帧以供下一次连接重发；
- 不在 writer 失败时关闭连接或触发重连；这由 `transport-adapter` 和 `connection-session` 处理；
- 不暴露或依赖 OkHttp、Ktor、WebSocket、Android、DJI、文件路径、线程池或 Jackson 类型。

## 3. 所有权和协作边界

| 事实 | 唯一拥有者 | 本模块的角色 |
| --- | --- | --- |
| 当前连接、五态、`sessionId` 与代次创建/失效 | `connection-session` | 只比对调用方提供的代次是否仍附着 |
| 帧字段、版本、大小和 UTF-8 JSON 编码 | `protocol-core` | 调用 `validate` 和 `encode`，不复制规则 |
| writer 的网络实现与关闭 | `transport-adapter` / `connection-session` | 只在附着期间调用 `writer.write(bytes)` |
| 命令、任务和遥测内容 | 各业务模块 | 仅发送已构造帧 |

`ActiveSession` 由 `connection-session` 在其处于 `ACTIVE` 时交给业务模块。业务模块把同一对象交给本模块发布；本模块只读取 `activeSession.generation`，绝不保存或输出 `sessionId`。

## 4. 对外接口

具体 Kotlin 名称可以微调，但公开能力必须等价于：

```text
OutboundPublisher : SessionOutbound

attach(generation, writer)
  -> AttachAccepted | AttachRejected

sendHandshake(generation, hello)
  -> SendAccepted | SendRejected

discard(generation)
  -> Unit

publish(activeSession, frame)
  -> Delivered
   | Rejected(STALE_SESSION | DIRECTION_NOT_ALLOWED | ENCODING_REJECTED | WRITE_REJECTED)
```

### 4.1 `attach`

- 只能由 `connection-session` 在同一 connection 的 `onOpened` 已被接受后调用；
- 没有当前附着时，保存 `generation` 和 `writer` 并返回 `AttachAccepted`；
- 已附着相同的 `generation` 和同一 `writer` 时幂等返回 `AttachAccepted`，不得写入任何字节；
- 已附着不同代次或不同 writer 时返回 `AttachRejected`，不得替换现有 writer；
- `attach` 不发送 `hello`，不编码任何帧，不访问网络以外的资源；
- writer 只保存在模块私有状态，调用方无法经本模块取回它。

### 4.2 `sendHandshake`

- 只能接受与当前附着代次相同的 `HelloFrame`；其他帧没有此接口；
- 同一附着代次最多成功调用一次；重复调用返回 `SendRejected`，不得写入第二个 `hello`；
- 先使用 `protocol-core.encode`，编码成功后才调用 writer；
- `encode` 被拒绝、抛出异常或 writer 返回 `WriteRejected` 时，返回 `SendRejected`，不泄漏原始帧、异常或字节；
- 成功只表示编码完成且 writer 接受了写入请求，不表示电脑收到或接受握手；
- 该方法是业务帧发布尚未开放前唯一允许的发送路径。

### 4.3 `publish`

- `activeSession.generation` 必须等于当前附着代次，否则返回 `Rejected(STALE_SESSION)` 且不编码、不写入；
- 允许的帧类型只有 `TelemetryFrame`、`CommandResultFrame` 和 `MissionResultFrame`；
- `HelloFrame` 必须走 `sendHandshake`；`PairedFrame`、`CommandFrame`、`MissionBeginFrame`、`MissionChunkFrame` 和 `MissionCompleteFrame` 都是当前方向不允许的帧，返回 `Rejected(DIRECTION_NOT_ALLOWED)`；
- 对允许帧，先调用 `protocol-core.encode`，成功后调用 writer；两个步骤在同一发送顺序中完成；
- 编码被拒绝或内部异常返回 `Rejected(ENCODING_REJECTED)`；writer 拒绝或抛出异常返回 `Rejected(WRITE_REJECTED)`；
- 成功返回 `Delivered`。这只表示 writer 已同步接受字节，不表示远端业务已执行；
- 不得保存调用方的 `RelayFrame`、编码后的 `ByteArray`、`ActiveSession` 或 `sessionId` 用于重连后的重发。

### 4.4 `discard`

- 只能由 `connection-session` 的固定结束流程调用；
- 代次与当前附着代次相同：原子清除 writer、附着代次和握手已发送标记；
- 代次不同或没有附着时：幂等空操作；
- `discard` 不关闭 writer，不调用网络库，不触发重连，不通知业务模块；
- `discard` 返回后，任何携带该代次或更早代次的 `sendHandshake` / `publish` 都不得再写入。

## 5. 顺序、并发和失效规则

- `attach`、`sendHandshake`、`publish` 与 `discard` 是线性化操作；它们在模块私有顺序出口中一次处理一个；
- writer 的每次 `write` 都只从该顺序出口发起，不允许业务线程直接持有或调用 writer；
- 对同一附着代次，写入字节的顺序等于操作在线性化点进入出口的顺序；
- 任何时刻最多执行一个 writer 写入；并发调用不得并行调用 writer；
- `discard` 与写入竞争时：要么当前写入先完整返回，再完成 discard；要么 discard 先完成，写入稳定返回 `STALE_SESSION`。不得在 discard 返回后开始该代次的新写入；
- writer 失败不会使本模块擅自失效代次。它只返回 `WRITE_REJECTED`；随后由 transport 回调驱动 `connection-session` 的结束流程；
- 新代次必须先由 session 对旧代次执行 `discard`，再 `attach`。本模块不接受直接覆盖；
- 本模块没有后台重试、定时器、队列恢复或跨代次缓存。

## 6. 结果模型和隐私

`PublishResult` 是不可变封闭结果：

```text
Delivered
Rejected {
  kind: STALE_SESSION
      | DIRECTION_NOT_ALLOWED
      | ENCODING_REJECTED
      | WRITE_REJECTED
}
```

- 结果不得包含 `sessionId`、完整 endpoint、原始 JSON、Base64、编码字节、异常、堆栈或 writer 对象；
- 错误分类只描述发送边界，不取代 `protocol-core` 的字段错误，也不取代 gateway 的业务错误；
- 调用方需要记录诊断时只能自行记录固定结果种类，不得从本模块获得未脱敏网络输入。

## 7. 依赖和可替换性

生产依赖只允许：

```text
Kotlin/JVM 标准库
connection-session 的公开 SessionOutbound、ActiveSession、SessionGeneration、TransportWriter 类型
protocol-core 的 RelayFrame、validate、RelayFrameCodec.encode 和结构化结果
```

内部实现可替换为锁、串行执行器或其他等价顺序机制，只要本契约的顺序、同步结果和失效语义保持不变。替换 transport 或 JSON 库不要求调用方修改。

## 8. 必测矩阵

- 首次 `attach`、相同 attachment 幂等、不同 writer 或不同代次拒绝；
- attach 不写字节；
- hello 正常发送、重复 hello 拒绝、旧代次 hello 拒绝、编码失败和 writer 拒绝；
- 三种允许业务帧分别编码并按调用顺序写入；
- 每种不允许方向帧稳定拒绝且不写字节；
- 没有附着、旧 `ActiveSession`、discard 后的发布都稳定返回 `STALE_SESSION`；
- `protocol-core` 编码失败和 writer 抛出异常不泄漏异常且不破坏下一次调用；
- 并发发布时 writer 最大并发数为一，接受的写入顺序与线性化顺序一致；
- `discard` 与并发发布竞争时，返回后不发生旧代次写入；
- 新代次不能覆盖旧 writer，只有旧代次 discard 后才可 attach；
- main 源码依赖扫描不得出现 Android、DJI、WebSocket、OkHttp、Ktor、文件系统或 JSON 第三方类型；
- 使用真实 `connection-session` 的公开接口验证 hello、握手后业务发布和结束流程 discard 的交接，不复制 SessionGeneration 或 ActiveSession 的创建逻辑。

## 9. 验收标准

只有全部条件同时成立，状态才可改为“已批准并实现”：

1. 模块只承担本契约的单一发送职责；
2. `connection-session` 继续只通过 `SessionOutbound` 与本模块交接；
3. 发送前统一经过 `protocol-core.encode`，没有第二套 JSON 或 Base64 规则；
4. 所有旧代次发送在 writer 前被拒绝；
5. 所有测试零失败、零错误、零跳过，且 `connection-session` 全量测试继续通过；
6. 依赖和架构扫描无越界项，`git diff --check` 无错误。

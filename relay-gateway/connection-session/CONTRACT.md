# relay-gateway.connection-session 二级模块契约

状态：已批准并实现
版本：0.1.0
父模块：[`../CONTRACT.md`](../CONTRACT.md)
协议依赖：[`../protocol-core/CONTRACT.md`](../protocol-core/CONTRACT.md)
模块标识：`relay-connection-session`

## 1. 模块身份

`connection-session` 的唯一职责是：**管理手机到一台电脑的当前 WebSocket 会话生命周期，完成 `hello / paired` 握手，并用不可复用的会话代次隔离新旧连接。**

通俗地说，这个模块只回答四个问题：

1. 现在是否应该连接电脑；
2. 当前连接进行到哪一步；
3. 这条连接是否已经完成握手，可以接收业务帧；
4. 某个异步回调是否仍属于当前连接。

调用方不需要知道 WebSocket 库、线程、定时器、JSON 解析或重连实现。替换网络库、调度器或内部并发模型，不得改变本契约规定的行为。

## 2. 负责与明确不负责

### 2.1 负责

- 响应上层的启动和停止请求；
- 保证任意时刻最多只有一个当前连接尝试；
- 为每次连接尝试创建一个新的本地会话代次；
- 在 transport 打开后发送且只发送一次 `hello`；
- 等待、校验并接受一个合法 `paired`；
- 维护 `STOPPED`、`CONNECTING`、`AWAITING_PAIRING`、`ACTIVE`、`RECONNECT_WAIT` 五种状态；
- 在握手超时、网络失败或连接关闭后失效当前代次；
- 在需要继续运行时安排一次自动重连；
- 让所有 transport 回调、定时器回调和会话事件按确定顺序处理；
- 只把当前 `ACTIVE` 会话的合法协议帧交给 gateway 后续模块；
- 在会话结束时按固定顺序触发命令取消、任务传输取消和发送队列清理；
- 发布不可变的会话快照和经过脱敏的会话结束原因。

### 2.2 明确不负责

- 不保存或修改电脑地址、端口和 `deviceId`；这些由 `relay-settings` 提供；
- 不创建稳定设备身份，也不把 `deviceId` 当作密码；
- 不实现 OkHttp、Ktor 或其他 WebSocket 库；这些属于 `transport-adapter`；
- 不定义 JSON 字段、协议限制或编码规则；这些属于 `protocol-core`；
- 不维护普通业务帧的发送队列和发送顺序；这些属于 `outbound-publisher`；
- 不按命令名寻找处理器，不关联命令结果；这些属于 `command-dispatcher`；
- 不接收、拼接或校验 KMZ 分块；这些属于 `mission-transfer`；
- 不生成遥测，不解释配对、直播或航线命令；
- 不调用 Android、DJI SDK、文件系统或数据库；
- 不在重连后自动恢复旧命令、旧任务传输或旧发送队列；
- 不提供 v1 之外的认证机制。未来增加认证前必须先修改根契约和协议契约。

## 3. 术语和身份区分

以下三个标识含义不同，任何实现都不得混用：

| 名称 | 来源 | 用途 | 是否发送到电脑 | 生命周期 |
| --- | --- | --- | --- | --- |
| `deviceId` | `relay-settings/device-identity` | 表示这台手机的稳定身份 | 是，放在 `hello` 中 | 跨进程重启保持稳定 |
| `sessionId` | 电脑端的 `paired` | 表示电脑认可的协议会话 | 由电脑发给手机 | 只在当前 `ACTIVE` 会话内有效 |
| `SessionGeneration` | `connection-session` | 隔离本机的新旧异步回调 | 否 | 每次连接尝试唯一，结束后永久失效 |

其他术语：

- **连接尝试**：从进入 `CONNECTING` 到该代次进入 `ACTIVE` 或被失效的全过程。
- **希望运行**：最近一次有效控制请求是 `start()`，且之后没有处理过 `stop()`。
- **当前代次**：唯一允许改变当前状态的 `SessionGeneration`。
- **旧回调**：携带的代次不是当前代次的 transport、定时器、发送完成或其他异步回调。
- **活动会话上下文**：由当前代次和当前 `sessionId` 组成的不可变值，只在 `ACTIVE` 时存在。
- **会话结束**：当前代次先被失效，再完成依赖清理，最后进入 `STOPPED` 或 `RECONNECT_WAIT`。

`sessionId` 即使与上一次连接相同，也不能证明是同一连接。是否属于当前连接只能比较 `SessionGeneration`。

## 4. 对外接口

以下名称表达固定语义，不强制实现使用完全相同的 Kotlin 类名：

```text
ConnectionSession.create(config, dependencies)
  -> ConnectionSession | ConfigurationRejected

ConnectionSession.start()
  -> StartAccepted
   | AlreadyRunning(snapshot)

ConnectionSession.stop()
  -> Stopped
   | AlreadyStopped

ConnectionSession.snapshot()
  -> SessionSnapshot

ConnectionSession.onStateChanged(listener)
  -> Registration
```

### 4.1 `create`

`create` 接收不可变配置和依赖。创建成功不建立网络连接，初始状态必须是 `STOPPED`。

配置项：

| 字段 | 约束 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `endpoint` | 已由 `endpoint-settings` 校验的非空值 | 无 | 模块不得重新解释 URL 业务规则 |
| `deviceId` | 已满足 `protocol-core` 的 `deviceId` 约束 | 无 | 用于构造 `hello` |
| `handshakeTimeoutMillis` | `1000..60000` | `10000` | 从 `hello` 被发送接口接受后开始计算 |
| `reconnectInitialDelayMillis` | `250..30000` | `1000` | 第一次自动重连的等待时间 |
| `reconnectMaxDelayMillis` | 不小于初始值，且不大于 `300000` | `30000` | 指数退避上限 |

`protocolVersion` 不是可由调用方随意设置的配置。当前实现必须使用 `protocol-core` 定义的版本 `"1"`，避免设置与协议模型出现两份真相。

任意配置不合法时，`create` 返回 `ConfigurationRejected`，不得创建半可用对象，不得尝试连接，也不得把原始 endpoint 或第三方异常写入错误详情。

### 4.2 `start`

- 在 `STOPPED` 调用时：设置为希望运行，清零连续失败次数，创建新代次并进入 `CONNECTING`；
- 在 `RECONNECT_WAIT` 调用时：取消唯一的待执行重连，保留连续失败次数，立即创建新代次并进入 `CONNECTING`；
- 在 `CONNECTING`、`AWAITING_PAIRING` 或 `ACTIVE` 调用时：返回 `AlreadyRunning`；
- `AlreadyRunning` 是幂等结果，不得创建第二个 transport、第二个代次、第二个握手定时器或第二条 `hello`；
- `StartAccepted` 只表示运行意图已被本模块接受，不携带快照或本地代次；
- 若 transport 在 `start()` 内同步返回 `OpenRejected`，仍返回 `StartAccepted`，但返回前必须完成失败清理；紧接着读取 `snapshot()` 必须得到 `RECONNECT_WAIT / null / null`；
- 其他成功受理情况下，紧接着读取 `snapshot()` 必须得到该次调用处理完成时的完整状态；调用方通过 `snapshot()` 和状态事件观察连接结果；
- `start()` 只表示连接流程已启动，不表示 WebSocket 已打开，更不表示 DJI 设备已连接。

### 4.3 `stop`

- 在 `CONNECTING`、`AWAITING_PAIRING` 或 `ACTIVE` 调用时：先取消“希望运行”，再执行 §9 的当前代次结束流程，最终进入 `STOPPED`；
- 在 `RECONNECT_WAIT` 调用时：取消“希望运行”和唯一重连定时器，清除等待令牌，直接提交 `STOPPED / null / null` 并排队一个 `EXPLICIT_STOP` 状态事件；此前代次已经完成清理，不得再次调用命令、任务或发送清理接口；
- 在 `STOPPED` 调用时：返回 `AlreadyStopped`，不得产生状态事件或网络操作；
- `stop()` 返回前，本地代次必须已经失效，握手定时器和重连定时器必须已经取消；
- `stop()` 返回前，若存在当前代次，§9 的清理调用必须全部完成或已被依赖明确接受；无论原状态为何，`STOPPED` 快照都必须已经提交；
- `stop()` 不等待远端确认关闭。稍后到达的 `onClosed` 或 `onFailure` 因代次已失效而被丢弃；
- `stop()` 返回后不得自动重连，也不得把旧连接的任何帧交给后续模块。

### 4.4 `snapshot`

`SessionSnapshot` 是不可变值，读取必须是线程安全的：

```text
SessionSnapshot {
  state: SessionState,
  generation: SessionGeneration?,
  sessionId: String?
}
```

字段组合固定如下：

| 状态 | `generation` | `sessionId` |
| --- | --- | --- |
| `STOPPED` | 空 | 空 |
| `CONNECTING` | 当前代次 | 空 |
| `AWAITING_PAIRING` | 当前代次 | 空 |
| `ACTIVE` | 当前代次 | 电脑端提供的非空值 |
| `RECONNECT_WAIT` | 空 | 空 |

快照不得包含 transport 实例、WebSocket 对象、线程、定时任务、原始 endpoint、异常对象或可变集合。

### 4.5 状态监听

监听器接收不可变事件：

```text
SessionStateEvent {
  previousState: SessionState,
  snapshot: SessionSnapshot,
  endReason: SessionEndReason?
}
```

```text
SessionEndReason {
  kind: EXPLICIT_STOP
      | NOT_CONNECTED
      | HANDSHAKE_TIMEOUT
      | INVALID_FRAME
      | UNSUPPORTED_FRAME
      | PROTOCOL_VERSION_UNSUPPORTED,
  detail: 不超过 256 个 Unicode code point 的脱敏短消息
}
```

`endReason` 只在一次运行阶段因为停止或失败进入 `STOPPED` 或 `RECONNECT_WAIT` 时存在；普通的启动、transport 打开和握手成功转换中必须为空。显式 `stop()` 使用 `EXPLICIT_STOP`，其他结束原因使用 §14 的固定映射。`detail` 不得为空，不得包含控制字符或第三方异常。

- 监听器注册后只接收注册之后发生的状态变化；当前状态由调用方主动读取 `snapshot()`；
- 每次真实状态变化只通知一次，幂等 `start()` 和幂等 `stop()` 不通知；
- 状态必须先提交，再把事件排入独立的顺序通知器；监听器不得在会话事件执行器上内联运行；
- 监听器必须使用事件自带的 `snapshot` 理解该次转换。监听器回调时主动读取 `snapshot()` 可以得到该事件状态或一个更新的状态，但绝不能得到比事件更旧的状态；
- 多个监听器按注册顺序调用；一个监听器抛出异常不得阻止其余监听器；
- 同一监听器的事件按会话转换顺序串行通知；慢监听器不得阻塞会话状态机；
- 监听器回调不得在模块内部锁中执行。回调中允许同步调用 `start()` 或 `stop()`，该调用由独立的会话事件执行器处理，因此不得死锁或重入修改当前转换；
- `start()` 和 `stop()` 返回时不要求对应状态事件已经实际送达监听器，只要求事件已按顺序进入通知器；
- 注销 `Registration` 必须幂等；注销返回前，已经开始执行的该监听器回调必须结束（监听器在自身回调中注销时不得等待自己），注销后不再启动新回调。

## 5. 内部协作接口

本模块通过小型接口依赖其他模块。生产适配器与测试替身必须满足同一语义。

```text
TransportConnector.open(endpoint, generation, listener)
  -> OpenAccepted(connection)
   | OpenRejected(sanitizedReason)

TransportConnection
  generation
  writer
  close(sanitizedReason)
    -> CloseRequested | AlreadyClosed

TransportListener
  onOpened(connection)
  onBytes(generation, bytes)
  onClosed(generation, sanitizedReason)
  onFailure(generation, sanitizedReason)

SessionOutbound
  attach(generation, writer)
    -> AttachAccepted | AttachRejected
  sendHandshake(generation, HelloFrame)
    -> SendAccepted | SendRejected
  discard(generation)

ActiveFrameConsumer.accept(activeSession, decodedFrame)

CommandSessionCleanup.cancel(generation, reason)
MissionSessionCleanup.abort(generation, reason)

MonotonicScheduler.schedule(delayMillis, callback)
  -> Cancellation

OrderedStateNotifier.enqueue(event, listeners)
```

规则：

- `TransportConnector`、`TransportConnection` 和 `writer` 是抽象接口，不得暴露具体 WebSocket 库类型；
- `OpenAccepted` 返回后，本模块立即拥有该 `TransportConnection`，直到代次结束；同一 handle 的 `close()` 必须幂等；
- `TransportConnection.generation` 必须等于 `open` 收到的代次；`onOpened` 必须带回同一个 connection，而不是创建第二个 handle；
- `TransportConnector.open` 返回前不得同步调用 listener。`OpenAccepted` 后的第一个回调只能是同一 connection 的 `onOpened`、`onClosed` 或 `onFailure`；
- `OpenRejected` 后不得再为该次 open 产生任何回调；
- connection 中的 writer 只有在该 connection 的 `onOpened` 被接受后才能交给 `SessionOutbound`；若先收到 `onClosed` 或 `onFailure`，writer 永远不得使用；
- `connection-session` 拥有 connection 的关闭权；`SessionOutbound` 只按代次借用 writer，不得自行关闭或跨代次保存它；
- transport 的每个回调都必须带回创建它时收到的代次；
- transport 只传字节，不解析 JSON；
- `connection-session` 必须先调用 `protocol-core` 解码，原始字节不得交给 `ActiveFrameConsumer`；
- `SessionOutbound` 负责发送顺序和实际写入，本模块不得另建发送队列；
- `sendHandshake` 是普通业务发布尚未开放前唯一允许的发送操作；
- `ActiveFrameConsumer` 只接收当前 `ACTIVE` 会话的非握手合法帧；
- `CommandSessionCleanup`、`MissionSessionCleanup` 和 `SessionOutbound.discard` 必须按 §9 的顺序调用；
- 三个清理接口必须对“该代次尚未产生任何工作”和重复清理返回幂等成功，不得要求连接曾经进入 `ACTIVE`；
- 调度器必须使用单调时间，墙上时钟调整不得提前或延后超时和重连；
- `OrderedStateNotifier` 与会话事件执行器相互独立，按事件顺序和监听器注册顺序调用，不得反向阻塞会话状态机；
- `ScheduledCancellation.cancel()` 的生产适配器必须幂等且不得抛出异常；若异常依赖仍然抛出，本模块必须先用代次或等待令牌使迟到回调逻辑失效，再记录脱敏诊断并继续清理；
- `ExecutorOrderedStateNotifier` 使用的 executor 必须把 drain 工作延后到独立执行上下文，不能以内联方式运行 listener；执行器暂时拒绝任务时，已入队事件必须保留，并在后续入队时继续尝试调度；
- 所有依赖都在 `create` 时注入，本模块不得从全局单例获取依赖。

`connection-session` 只依赖 `protocol-core` 已公开的帧模型和 `decode` 结果。字段格式、版本字段校验和 `Decoded / Rejected / Ignored` 的产生由 `protocol-core` 负责；当前连接处于五态中的哪一态、当前是否允许 `paired`，由本模块负责。出站 `hello` 以 `HelloFrame` 交给 `SessionOutbound`，由发送模块统一调用 `protocol-core.encode`，本模块不保存编码字节。

本模块不得调用或维护任务传输状态机。`mission-begin/chunk/complete` 在 `ACTIVE` 后原样交给 gateway 后续路由，顺序、累计字节、摘要和取消只由 `mission-transfer` 拥有。这样不会在会话模块和任务模块之间形成两份传输状态。

### 5.1 与 `protocol-core` 状态规则的关系

`protocol-core` 契约 §4 描述的是所有调用方必须遵守的**协议转换规则**，以及可以由纯 JVM 代码实现和测试的规则算法；它不是“当前手机会话”或“当前任务传输”的运行状态仓库。

运行状态所有权固定为：

| 运行中的事实 | 唯一拥有者 | `protocol-core` 的角色 |
| --- | --- | --- |
| 当前五态、当前代次、当前 `sessionId` | `connection-session` | 定义帧和字段是否合法，不保存全局或跨代次当前会话 |
| 当前任务 ID、累计字节、摘要和传输阶段 | `mission-transfer` | 提供可复用的纯规则实现，不拥有 gateway 中的当前传输实例 |

因此：

- 本模块只保存一份当前握手状态，不在 `protocol-core` 中建立第二个全局当前会话；
- 本模块当前不依赖 `protocol-core` 契约尚未公开的有状态接口；
- 若以后希望复用 `protocol-core` 的状态机实现，必须先在其契约中公开一个纯规则接口，再由本模块按代次私有持有实例；该实例仍属于本模块的实现，不改变状态所有权；
- 任务传输状态机只能由未来 `mission-transfer` 契约决定如何持有，本模块不得实例化它；
- `protocol-core` 的规则测试、`connection-session` 的生命周期测试和 `mission-transfer` 的传输测试验证不同层次，不得用其中一组替代另一组。

`TransportConnector.open` 的网络连接超时由 `transport-adapter` 配置并以 `onFailure` 或 `OpenRejected` 报告；本模块只拥有握手超时，不能同时维护第二份连接超时规则。

## 6. 状态机

### 6.1 合法转换

```text
STOPPED
  -- start --> CONNECTING

CONNECTING
  -- transport opened and hello accepted --> AWAITING_PAIRING
  -- open rejected / closed / failed --> RECONNECT_WAIT
  -- stop --> STOPPED

AWAITING_PAIRING
  -- valid paired --> ACTIVE
  -- timeout / closed / failed / illegal handshake --> RECONNECT_WAIT
  -- stop --> STOPPED

ACTIVE
  -- closed / failed / duplicate handshake --> RECONNECT_WAIT
  -- stop --> STOPPED

RECONNECT_WAIT
  -- retry timer / start --> CONNECTING
  -- stop --> STOPPED
```

### 6.2 禁止转换

- `STOPPED` 不能直接进入 `AWAITING_PAIRING` 或 `ACTIVE`；
- `CONNECTING` 不能在 transport 未打开时发送 `hello`；
- `CONNECTING` 不能仅因 WebSocket 打开而进入 `ACTIVE`；
- `AWAITING_PAIRING` 不能因普通命令帧、任务帧或未知帧进入 `ACTIVE`；
- `ACTIVE` 不能用第二个 `paired` 替换当前 `sessionId`；
- `RECONNECT_WAIT` 不能保留上一代次、上一 `sessionId` 或上一发送 writer；
- 任意旧回调都不能触发状态转换。

### 6.3 状态不变量

- 任意时刻最多一个当前代次；
- 任意时刻最多一个当前 transport 连接尝试；
- 任意时刻最多一个握手超时任务；
- 任意时刻最多一个重连等待任务；
- 只有 `ACTIVE` 存在活动会话上下文；
- 只有 `ACTIVE` 可以向 `ActiveFrameConsumer` 交付业务帧；
- 一旦开始结束流程，当前代次必须先失效，之后才能调用任何清理依赖；
- 结束流程开始时必须一次性提交符合 §4.4 的目标快照，清理期间也不能暴露半更新字段组合；
- 状态事件、活动帧事件和结束原因必须按本模块处理事件的顺序发布。

## 7. `hello / paired` 握手

### 7.1 正常顺序

每次连接尝试严格执行以下步骤：

1. 在进入 `CONNECTING` 前创建新代次；
2. 用 endpoint、代次和 transport listener 请求打开连接；
3. `open` 返回 `OpenAccepted` 后先保存并拥有 connection；只接受该 connection 第一次合法的 `onOpened`；
4. 从 `onOpened` 携带的同一 connection 取得 writer，并把 writer 和代次交给 `SessionOutbound.attach`，只有 `AttachAccepted` 才继续；
5. 使用 `protocol-core` 的帧模型构造：

   ```text
   hello {
     type: "hello",
     deviceId: 配置中的稳定 deviceId,
     protocolVersion: "1"
   }
   ```

6. 调用 `SessionOutbound.sendHandshake`，且同一代次最多调用一次；
7. 只有发送接口返回 `SendAccepted` 后，才把会话状态改为 `AWAITING_PAIRING` 并启动握手定时器；
8. 收到合法 `paired` 后取消握手定时器，保存 `sessionId`，进入 `ACTIVE`；
9. 进入 `ACTIVE` 后发布一次活动状态，后续模块才可以发送遥测或处理业务帧。

`SessionOutbound` 负责调用 `protocol-core.encode` 并写入 writer。`SendAccepted` 只表示发送接口已经接受且成功编码该帧，不保证电脑已经收到。电脑是否接受会话只能由合法 `paired` 证明。

### 7.2 合法 `paired`

合法帧必须满足：

- `type` 是 `paired`；
- `sessionId` 满足 `protocol-core` 的非空、长度和控制字符限制；
- `protocolVersion` 为 `"1"`，或者字段缺失；
- 字段缺失时按 v1 处理，这是当前电脑端兼容要求；
- 帧属于当前代次，并且当前状态恰好是 `AWAITING_PAIRING`。

接受后形成的活动会话上下文为：

```text
ActiveSession {
  generation: 当前 SessionGeneration,
  sessionId: paired.sessionId
}
```

该值不可变。连接存续期间不得替换其中任一字段。

### 7.3 非法、重复和乱序握手

| 情况 | 行为 | 是否进入 `ACTIVE` |
| --- | --- | --- |
| transport 打开前收到任何字节 | 丢弃并记录适配器违规，不启动握手 | 否 |
| `AWAITING_PAIRING` 收到合法但未知的扩展帧 | 忽略，握手定时器继续 | 否 |
| `AWAITING_PAIRING` 收到普通已知业务帧 | 以 `UNSUPPORTED_FRAME` 结束当前代次并重连 | 否 |
| `AWAITING_PAIRING` 收到损坏或字段非法的帧 | 以 `INVALID_FRAME` 结束当前代次并重连 | 否 |
| `paired.protocolVersion` 不是 `"1"` | 以 `PROTOCOL_VERSION_UNSUPPORTED` 结束当前代次并重连 | 否 |
| `ACTIVE` 收到第二个合法 `paired` 或手机方向的 `hello` | 以 `UNSUPPORTED_FRAME` 结束当前代次并重连 | 已有会话先失效 |
| 同一 connection 重复收到 `onOpened` | 忽略并记录适配器违规，不重发 `hello` | 不改变 |
| 超时或断线后才收到 `paired` | 作为旧回调丢弃 | 否 |

`ACTIVE` 中的处理规则必须可由 `protocol-core` 的结构化结果和帧类型确定，不得重新扫描原始 JSON：

- `Decoded(HelloFrame)` 或 `Decoded(PairedFrame)` 属于重复握手，以 `UNSUPPORTED_FRAME` 结束当前会话；
- `Rejected(PROTOCOL_VERSION_UNSUPPORTED)` 以版本不兼容结束当前会话；
- 其他 `Rejected` 结果只丢弃当前帧并报告脱敏的 `INVALID_FRAME` 诊断，保持健康会话；
- 其他 `Decoded` 帧才可以交给后续模块。

未知扩展帧由 `protocol-core` 返回 `Ignored`。无论握手前后，`Ignored` 都不交给业务模块，也不导致断线。

### 7.4 握手超时

- 超时从 `hello` 被 `SessionOutbound` 接受后开始，而不是从 `start()` 或 transport 打开时开始；
- 超时任务必须携带创建它的代次；
- 截止前先被事件序列处理的合法 `paired` 获胜，随后到达的超时回调被丢弃；
- 超时回调先被处理时，代次立即失效，随后到达的 `paired` 被丢弃；
- 超时结束原因固定为 `HANDSHAKE_TIMEOUT`，不得伪装成正常断开；
- 超时后必须执行完整结束流程，不能复用同一 transport 再发一次 `hello`。

## 8. 会话代次和旧回调隔离

### 8.1 创建规则

- 每次从 `STOPPED` 或 `RECONNECT_WAIT` 进入 `CONNECTING` 都创建新代次；
- 代次是只能比较是否相等的不透明值，调用方不得依赖其数字大小、字符串格式或生成算法；
- 同一进程内已经使用过的代次不得再次使用；
- 代次不持久化，不写入协议帧，不作为认证信息；
- 进程重启后可以重新建立代次命名空间，但不得依靠持久化的旧代次恢复操作。

### 8.2 失效规则

发生以下任一情况时，必须在其他清理动作之前原子地失效当前代次：

- 显式 `stop()`；
- transport 打开失败、异常关闭或失败；
- `SessionOutbound` 拒绝 attach，或 `hello` 编码/发送失败；
- 握手超时；
- 非法、重复或版本不兼容的握手；

代次一旦失效，永远不能恢复。自动重连只能创建新代次。

### 8.3 本模块必须隔离的回调

以下每一种回调都必须在处理前比较代次：

- `onOpened`；
- `onBytes`；
- `onClosed`；
- `onFailure`；
- 握手超时；
- 重连定时器回调必须比较自己的等待令牌；它没有上一代次，也不得复用代次代替等待令牌。

代次不匹配时只能：

1. 丢弃回调；
2. 对迟到的旧 `onOpened(connection)` 调用该旧 connection 自己的幂等 `close()`，不得查询或关闭当前 connection；
3. 写入不包含原始载荷和异常堆栈的内部诊断。

代次不匹配时绝对不得：

- 修改当前状态或 `sessionId`；
- 取消当前连接的定时器；
- 调用当前连接的清理接口；
- 发布命令结果、任务结果或遥测；
- 把旧帧重新标记为当前代次；
- 通知调用方“新会话失败”。

### 8.4 兄弟模块的代次义务

业务异步操作启动时必须捕获本模块交付的 `ActiveSession`，但命令完成、任务完成和发送完成回调不会回到 `connection-session`。它们分别由 `command-dispatcher`、`mission-transfer` 和 `outbound-publisher` 在自己的契约中验证代次。

本模块只负责创建、失效和交付代次，不负责替兄弟模块检查其私有回调。任何兄弟模块都不得要求本模块给旧结果创建新代次，也不得把旧结果重新标记为当前结果。后续模块契约必须分别测试这一规则。

## 9. 会话结束和清理顺序

本节主流程只适用于存在当前代次的 `CONNECTING`、`AWAITING_PAIRING` 和 `ACTIVE`。每个代次最多执行一次结束流程。`onFailure`、`onClosed`、超时和 `stop()` 即使几乎同时到达，也只有第一个针对当前代次的事件能够开始清理。

顺序固定为：

1. **停止接收并提交目标快照**：原子失效当前代次、清除活动会话上下文，并把只读快照提交为 `STOPPED / null / null` 或 `RECONNECT_WAIT / null / null`，但暂不调用状态监听器；
2. **取消会话定时器**：取消握手超时；显式停止还要取消重连等待；
3. **关闭 transport**：若 `open` 曾返回 `OpenAccepted`，对该代次拥有的 `TransportConnection` 调用一次幂等 `close()`；同步 `OpenRejected` 时本步为空操作；
4. **取消命令等待**：调用 `CommandSessionCleanup.cancel(generation, reason)`；
5. **取消任务传输**：调用 `MissionSessionCleanup.abort(generation, reason)`；
6. **清空发送状态**：调用 `SessionOutbound.discard(generation)`，丢弃该代次未发送和发送中的业务帧；
7. **删除会话临时引用**：清除当前 writer 和 `TransportConnection` 引用；
8. **准备后续动作**：需要继续运行时创建唯一重连等待，否则保持无定时器；
9. **排队状态通知**：按第 1 步已经提交的目标快照构造一次 `SessionStateEvent`，最后交给 `OrderedStateNotifier`；实际监听器随后异步顺序执行。

要求：

- 任何清理依赖抛出异常都不得中断后续清理；模块必须记录脱敏诊断并继续；
- 清理异常不得让旧代次重新有效，也不得把结束状态伪装成 `ACTIVE`；
- 状态事件入队必须是结束流程的最后一类外部调用；监听器实际被调用时，前面的清理均已被请求；
- 清理期间并发读取 `snapshot()` 时，只能看到结束前的完整快照或第 1 步提交的完整目标快照，不得看到 `ACTIVE / null / null` 等非法组合；
- transport 随后回调 `onClosed` 或 `onFailure` 时，由于代次已失效，只能丢弃；
- 本模块只要求 `mission-transfer` 中止传输，不直接删除 KMZ 文件；实际文件生命周期属于 `mission-staging`；
- 断线后未确认的命令和传输都视为未确认，不得在新会话中自动继续。

### 9.1 无当前代次的停止

`RECONNECT_WAIT` 表示上一代次已经完整执行上述流程，因此没有 generation、transport、writer、活动上下文或待清理业务状态。从该状态调用 `stop()` 只允许按以下顺序执行：

1. 取消希望运行；
2. 取消唯一重连定时器并失效等待令牌；
3. 提交 `STOPPED / null / null`；
4. 向顺序通知器排入一个 `EXPLICIT_STOP` 状态事件；
5. 返回 `Stopped`。

这条路径不得调用 transport close、命令取消、任务中止或 outbound discard，否则会重复清理上一代次。

## 10. 自动重连

### 10.1 何时重连

当前请求仍是希望运行，并且连接尝试因非显式停止而结束时，清理完成后进入 `RECONNECT_WAIT`。

以下情况都采用自动重连：

- transport 同步拒绝打开；
- `CONNECTING`、`AWAITING_PAIRING` 或 `ACTIVE` 中收到 `onClosed` 或 `onFailure`；
- `hello` 发送失败；
- 握手超时；
- 非法、重复或版本不兼容的握手。

显式 `stop()` 永远不自动重连。Android 前后台、网络可用性和权限变化由 `app-runtime` 决定何时调用 `start()` 或 `stop()`；本模块不直接观察 Android 生命周期。

### 10.2 退避规则

连续失败次数记为 `n`，第一次失败后 `n = 1`。等待时间固定为：

```text
min(reconnectInitialDelayMillis * 2^(n - 1), reconnectMaxDelayMillis)
```

默认配置对应：

```text
1s, 2s, 4s, 8s, 16s, 30s, 30s, ...
```

规则：

- 进入 `ACTIVE` 时连续失败次数清零；
- 仅 transport 打开但未收到合法 `paired` 不算成功，失败次数不得清零；
- v1 不增加随机抖动，保证行为和测试确定；
- 任意时刻最多一个有效重连定时器；
- 每个重连定时器使用不可复用的等待令牌，取消后的迟到回调只能丢弃；
- 重连定时器触发时创建新代次并进入 `CONNECTING`；
- 在 `RECONNECT_WAIT` 调用 `start()` 会取消原定时器并立即尝试，但不清零失败次数；
- 在 `RECONNECT_WAIT` 调用 `stop()` 会取消原定时器并进入 `STOPPED`；
- 重连不复用 transport、writer、`sessionId`、握手定时器、业务队列或任务传输状态。

## 11. 接收帧规则

所有 `onBytes` 按以下顺序处理：

1. 比较回调代次；
2. 确认状态允许接收；
3. 使用 `protocol-core` 解码和字段校验；
4. `Ignored` 直接丢弃，`Rejected` 按 §7.3 处理；
5. 对 `Decoded` 的 `HelloFrame` 和 `PairedFrame` 执行 §7 的握手状态规则；
6. 仅在 `ACTIVE` 时把其余 `Decoded` 帧连同不可变 `ActiveSession` 交给 gateway 后续模块。

本模块不得：

- 把原始 `ByteArray`、原始 JSON 或第三方解析异常交给业务模块；
- 修改合法业务帧的字段；
- 判断 `telemetry.read`、`wayline.*` 或 `live-stream.*` 是否应该成功；
- 解析 KMZ 分块内容；
- 把握手前收到的业务帧缓存到握手后执行；
- 在重连后重放旧连接收到的任何帧。

普通业务帧的方向、命令名和任务传输顺序由后续 gateway 模块校验。本模块只保证交接时会话已经 `ACTIVE` 且代次仍然有效。

## 12. 数据所有权和持久化

### 12.1 本模块唯一拥有

- 是否希望运行；
- 当前五态之一；
- 当前代次或重连等待令牌；
- 当前 transport handle 和 writer 的抽象引用；
- 当前握手超时和重连定时器的取消句柄；
- 当前 `sessionId` 和活动会话上下文；
- 连续失败次数；
- 本代次是否已经执行结束流程。

### 12.2 本模块只读使用

- 已校验 endpoint；
- 稳定 `deviceId`；
- `protocol-core` 的版本、帧模型和编解码结果；
- 注入的时间配置。

### 12.3 本模块不得拥有

- 遥测、设备连接、配对、直播或航线业务状态；
- 命令注册表、命令执行结果或任务文件字节；
- 发送队列内容；
- Android 生命周期对象；
- DJI SDK 对象；
- 电脑端保存的会话历史。

所有会话数据只存在内存。进程重启后从 `STOPPED` 和零连续失败次数开始；只有 `deviceId` 由外部持久化。不得持久化或恢复 `SessionGeneration`、`sessionId`、定时器和未确认业务操作。

## 13. 并发和事件顺序

- `start()`、`stop()`、transport 回调、定时器回调和内部发送结果必须在线性化的会话事件序列中处理；
- `start()` 和 `stop()` 可以等待各自的会话事件处理完成后同步返回，但会话事件执行器绝不直接调用状态监听器；
- transport 可以从任意线程回调，但本模块必须自行串行化，不能要求网络库碰巧单线程；
- 同一连接收到的字节必须保持 transport 提供的先后顺序；
- 状态修改必须原子，`snapshot()` 不能看到表中未定义的字段组合；
- `paired`、超时和断线同时发生时，以事件序列中第一个针对当前代次的事件为准，后续事件因代次失效而丢弃；
- 并发 `start()` 最多创建一个连接尝试，其余返回 `AlreadyRunning`；
- 并发 `stop()` 最多执行一次结束流程，其余返回 `AlreadyStopped` 或等价幂等结果；
- 并发 `start()` 与 `stop()` 按线性化顺序解释。最后处理的是 `stop()` 时最终必须为 `STOPPED`；最后处理的是 `start()` 时可以开始一个全新代次；
- cleanup 和 `ActiveFrameConsumer` 不得在内部锁中调用；这些接口的数据流是单向的，其实现不得反向同步调用本模块的 `start()` 或 `stop()`；
- 状态监听器只在独立顺序通知器上执行，可以同步调用 `start()` 或 `stop()`，因此不会等待正在调用自己的会话事件；
- `ActiveFrameConsumer` 抛出异常时只记录脱敏的内部诊断并丢弃该帧，不能让异常穿出会话事件序列，也不能因此重新交付同一帧；
- 模块不得阻塞等待网络、睡眠或使用墙上时钟实现退避；
- 一个慢状态监听器不得阻塞会话事件执行器，也不得打乱同一监听器的状态顺序。

## 14. 错误分类和对外映射

本模块只产生连接与握手类结果，不产生业务命令结果帧。

| 条件 | gateway 对外分类 | 会话行为 |
| --- | --- | --- |
| 显式 `stop()` | 无，内部原因为 `EXPLICIT_STOP` | 清理后进入 `STOPPED`，不重连 |
| transport 打开失败、关闭或网络失败 | `NOT_CONNECTED` | 结束代次并按策略重连 |
| `SessionOutbound.attach` 拒绝，或 `hello` 编码/发送失败 | `NOT_CONNECTED` | 结束代次并按策略重连 |
| 等待 `paired` 超时 | `HANDSHAKE_TIMEOUT` | 结束代次并按策略重连 |
| 握手帧损坏或字段非法 | `INVALID_FRAME` | 结束代次并按策略重连 |
| 握手阶段收到已知但不允许的帧，或重复握手 | `UNSUPPORTED_FRAME` | 结束代次并按策略重连 |
| `paired` 明确给出不支持的版本 | `PROTOCOL_VERSION_UNSUPPORTED` | 结束代次并按策略重连 |
| 普通业务帧损坏 | `INVALID_FRAME` 诊断 | 丢弃单帧，保持当前健康会话 |
| 未知扩展帧 | 无 | 忽略 |
| 旧代次回调 | 无 | 静默丢弃，可记录限频诊断 |
| 活动帧 consumer 抛出异常 | 仅内部脱敏诊断 | 丢弃该帧，保持会话，不自动重试该帧 |
| 清理依赖异常 | 仅内部脱敏诊断 | 继续其余清理并进入目标状态 |

错误内容要求：

- 只使用封闭的错误分类和短消息；
- 不包含原始 WebSocket 数据、原始 JSON、完整 endpoint、查询参数、认证信息、`deviceId` 全值、`sessionId` 全值或第三方异常堆栈；
- 可以在手机本地诊断中记录异常类别、状态、是否当前代次和限长摘要；
- 未完成握手时没有可靠的电脑会话，不得尝试发送一个“握手失败结果帧”；
- 连接失败不等于 DJI 设备失败，不能映射为配对、直播或航线错误。

## 15. 安全和隐私

- v1 的 `hello / paired` 只做协议握手，不做身份认证；
- `deviceId` 是标识，不是凭证；`sessionId` 和 `SessionGeneration` 也都不是安全令牌；
- 本模块不得自行发明 pairing token、共享密钥或证书字段；新增认证必须先修改双端根契约和协议版本策略；
- 未处于 `ACTIVE` 时不得执行或缓存任何业务帧；
- 旧代次隔离是并发正确性要求，不应被描述成网络认证机制；
- 日志和状态监听不得暴露 endpoint 中的用户名、密码、token 或查询参数；
- 任意来自网络的字节都必须先经过 `protocol-core`，不得反序列化成 Android、DJI 或任意可执行类型。

## 16. 依赖替身和测试环境

本模块全部规则必须在纯 Kotlin/JVM 环境验证，不需要 Android 设备、DJI SDK 或真实网络。

测试使用：

| 依赖 | 测试替身能力 |
| --- | --- |
| `TransportConnector` | 记录打开次数、代次和 endpoint，可返回具名 connection 或同步拒绝 |
| `TransportConnection` | 暴露相同代次和 writer，记录幂等关闭，可在 open 返回后主动触发任意 transport 回调 |
| transport writer | 记录发送，可返回成功或失败 |
| `SessionOutbound` | 记录 attach、握手发送和 discard 顺序，可模拟发送拒绝 |
| `protocol-core` | 使用真实纯 JVM 编解码实现，不复制一套测试协议 |
| `ActiveFrameConsumer` | 记录收到的活动上下文和不可变帧 |
| 两个 cleanup 接口 | 记录调用顺序，可模拟抛出异常 |
| 单调调度器 | 手动推进时间，精确触发或取消超时和重连 |
| 顺序状态通知器 | 手动排空事件，证明通知不阻塞会话执行器且保持顺序 |
| 状态监听器 | 记录状态、顺序、注销和异常隔离 |

真实 WebSocket 集成测试属于未来 `transport-adapter`，只能补充本测试矩阵，不能替代本模块的纯 JVM 测试。

## 17. 必测矩阵

### 17.1 配置和初始状态

- 合法配置创建成功且不连接，快照为 `STOPPED / null / null`；
- handshake timeout 的最小值、最大值、低于最小值和高于最大值；
- reconnect initial delay 的最小值、最大值和越界值；
- reconnect max 小于 initial、等于 initial、等于上限和高于上限；
- 空或未经校验的 endpoint、非法 `deviceId` 被拒绝且不调用 transport；
- 调用方不能通过配置改成非 v1 协议版本。

### 17.2 启动、停止和幂等

- `STOPPED -> start -> CONNECTING` 只创建一个代次和一次 open；
- `StartAccepted` 不携带快照或代次；同步 `OpenRejected` 时，返回后主动读取的快照已经是 `RECONNECT_WAIT / null / null`；
- 分别在 `CONNECTING`、`AWAITING_PAIRING`、`ACTIVE` 重复 `start()`，没有额外连接、`hello` 或定时器；
- 在 `RECONNECT_WAIT` 调用 `start()`，旧重连任务被取消且立即创建新代次；
- 分别在五种状态调用 `stop()`，最终都满足对应规则；
- 重复 `stop()` 不重复关闭、清理或通知；
- `stop()` 返回后迟到的 close/failure/bytes/timer 不触发重连或状态变化；
- `RECONNECT_WAIT -> stop` 只取消重连并排队 `EXPLICIT_STOP` 事件，不重复调用上一代次的四类清理操作；
- `stop()` 返回时状态事件可以尚未送达，但已经进入顺序通知器。

### 17.3 正常握手

- transport 打开后发送一次且只发送一次正确的 `hello(deviceId, "1")`；
- `open` 返回前不能回调；`OpenAccepted` 返回的 connection 与 `onOpened` 携带的对象和代次相同；
- `hello` 被接受后才进入 `AWAITING_PAIRING` 并启动超时；
- `paired(sessionId, "1")` 激活会话；
- 缺少 `paired.protocolVersion` 时按 v1 激活会话；
- 激活后超时任务已取消，状态事件只发布一次；
- 活动快照包含当前代次和 `sessionId`；
- 握手后的合法业务帧携带相同活动上下文交给 consumer；
- 断线后活动上下文被清除。

### 17.4 非法和乱序握手

- transport 打开前到达 `paired`、业务帧、未知帧和损坏字节；
- `AWAITING_PAIRING` 收到 command、mission、telemetry、result 和 `hello`；
- 空、超长、含控制字符的 `sessionId`；
- `protocolVersion` 为 `"1"`、缺失、空字符串、未知主版本和错误类型；
- 同一 connection 重复 `onOpened` 不重发 `hello`；
- `ACTIVE` 中重复 `paired` 或收到 `hello` 会结束旧会话而不是替换 `sessionId`；
- 握手前后的未知扩展帧都被忽略；
- 握手期间的非法帧导致结束，活动期间单个普通非法帧只被丢弃；
- `SessionOutbound.attach` 或 `sendHandshake` 失败时不会进入 `ACTIVE`；
- connection-session 只调用 `protocol-core` 的帧模型和编解码接口，不创建任务传输状态；

### 17.5 超时和重连

- `hello` 发送前推进时间不触发握手超时；
- `hello` 接受后在截止前收到 `paired`；
- 截止时超时先处理和 `paired` 先处理的两种确定结果；
- 超时后迟到 `paired` 被丢弃；
- open 同步拒绝、每个状态下 close、每个状态下 failure 都进入重连流程；
- 默认退避序列为 `1, 2, 4, 8, 16, 30, 30` 秒；
- 自定义合法初始值和上限使用相同公式；
- 仅 transport 打开不重置失败次数，进入 `ACTIVE` 才重置；
- 任意时刻只有一个重连任务；取消任务的迟到回调被丢弃；
- `RECONNECT_WAIT` 中 `start()` 立即重连但保留失败次数；
- `RECONNECT_WAIT` 中 `stop()` 永久取消当前等待，除非以后再次调用 `start()`。

### 17.6 代次隔离

- 每次连接尝试得到新代次，已结束代次不复用；
- 新旧 `sessionId` 相同也不能让旧回调通过；
- 对旧代次分别触发 opened、bytes、closed、failure 和 handshake timeout；
- 对已取消的旧等待令牌触发 reconnect timer；
- 每一种旧回调都不改变当前快照、不清理当前会话、不发布状态和业务帧；
- 迟到旧 `onOpened` 只关闭旧 transport，不关闭当前 transport；
- 交给 consumer 的 `ActiveSession` 包含创建业务操作所需的原始代次，且本模块不提供“换成新代次”的入口；
- 进程重启模型不恢复旧代次、旧 `sessionId` 或未确认操作。

### 17.7 清理顺序和容错

- 显式停止、网络关闭、网络失败、握手超时、握手拒绝分别执行完整清理；
- 断言顺序严格为：失效接收、取消定时器、关闭 transport、取消命令、取消任务、discard 发送、清除引用、准备后续动作、状态通知；
- 同一代次同时收到 failure、closed、timeout 和 stop，只清理一次；
- 每个 cleanup 依赖分别抛出异常时，其余步骤仍执行；
- 握手或重连定时器的取消依赖抛出异常时，仍记录脱敏诊断、使迟到回调失效并完成停止或重连状态转换；
- listener 抛出异常时其他 listener 仍收到通知；
- listener 正在执行时并发注销必须等待其结束，listener 自注销不得死锁；
- 清理开始后到达的业务字节不交给 consumer；
- 状态 listener 执行时旧活动上下文和发送状态已经不可用；
- 清理期间反复读取快照不会观察到非法字段组合；
- `RECONNECT_WAIT -> stop` 不重复执行 transport、命令、任务或 outbound 清理；
- 断线不会自动恢复旧命令、旧任务传输或旧发送帧。

### 17.8 并发、健壮性和隐私

- 从多个线程并发触发 start、stop、transport 和 timer 事件，结果满足一种合法线性顺序且不存在两个当前连接；
- listener 内重入 `snapshot()`、`start()`、`stop()` 不死锁、不破坏当前转换；
- listener 未被排空时 `stop()` 仍可完成；listener 中同步 `stop()` 由独立会话执行器完成；
- active frame consumer 抛出异常时，会话事件序列继续工作且同一帧不重复交付；
- 慢 listener 不导致同一 listener 的状态事件乱序；
- 状态通知执行器暂时拒绝一次任务后，后续事件仍按原顺序送达且不丢失此前事件；
- 随机生成长事件序列，持续断言 §6.3 的全部不变量；
- 任意输入和依赖异常都不会向调用方泄漏 WebSocket 库异常；
- 错误和诊断不包含原始字节、原始 JSON、完整 endpoint、token、完整标识或异常堆栈；
- 模块测试的运行时依赖中不存在 Android 或 DJI SDK。

## 18. 变更规则

以下改动可以只改变实现，不修改本契约：

- 替换 WebSocket 库或定时器实现；
- 把锁实现替换为 actor、协程或单线程事件循环；
- 优化内部对象分配、日志和指标；
- 在不改变顺序、结果和时间语义的前提下重构内部类。

以下改动必须先修改本契约并重新审阅：

- 增删公开状态、入口、结果或活动会话字段；
- 改变 `start()`、`stop()`、幂等、超时、重连或清理行为；
- 改变代次的创建、失效或比较规则；
- 允许握手前缓存业务帧，或允许重连后恢复旧操作；
- 改变哪些帧会关闭会话；
- 改变默认时间、合法范围或退避公式。

以下改动还必须同步修改上级契约、电脑端契约和双端测试：

- 改变 `hello / paired` 字段、方向、顺序或成功条件；
- 不再兼容缺少 `paired.protocolVersion` 的 v1 电脑端；
- 改变协议主版本；
- 增加认证、token、证书或会话恢复字段；
- 把 `SessionGeneration` 放到网络协议中；
- 允许同一手机同时存在多个当前电脑会话。

## 19. 验收标准

本模块只有同时满足以下条件才可进入实现阶段：

- 调用方不阅读实现即可解释五种状态和全部合法转换；
- `deviceId`、`sessionId` 和 `SessionGeneration` 的用途没有重叠；
- 接口不暴露 Android、DJI、具体 WebSocket、线程池或可变状态；
- 握手成功、失败、超时、重复、错序和版本兼容都有唯一行为；
- 显式停止和异常断线的重连行为明确不同；
- 清理顺序固定，依赖异常不会留下仍有效的旧代次；
- 每一种异步回调都受代次检查保护；
- 全部必测项可以使用纯 JVM 替身完成；
- 本契约与根契约、`relay-gateway` 契约和 `protocol-core` 契约不存在功能范围冲突；
- 契约经审阅确认后，才能编写实现和对应测试。

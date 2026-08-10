# relay-gateway.command-dispatcher 二级模块契约

状态：已批准并实现
版本：0.1.0
父模块：[`../CONTRACT.md`](../CONTRACT.md)
协议依赖：[`../protocol-core/CONTRACT.md`](../protocol-core/CONTRACT.md)
会话依赖：[`../connection-session/CONTRACT.md`](../connection-session/CONTRACT.md)
发送依赖：[`../outbound-publisher/CONTRACT.md`](../outbound-publisher/CONTRACT.md)
模块标识：`relay-command-dispatcher`
模块目录：`src/modules/relay-gateway/command-dispatcher/`
Gradle 路径：`:relay-gateway:command-dispatcher`

本文是 `command-dispatcher` 唯一有效的设计、使用说明和验收依据。实现和测试只能遵守本文；不允许把业务约定、线程行为或第三方 SDK 的细节隐藏在调用方中。

## 1. 唯一职责

`command-dispatcher` 只负责一件事：**把当前活动会话收到的 `CommandFrame` 按命令名交给已注册处理器，并把每个命令 ID 的唯一终态结果交给发送模块。**

它把注册表、重复 ID、防止旧会话结果发布、异步完成竞争、处理器异常和结果脱敏隐藏在一个小接口之后。调用方不需要知道命令是否同步完成、哪个处理器被选中，或结果如何顺序发送。

本模块是纯 Kotlin/JVM 模块。没有 Android、DJI SDK、真实网络、文件系统或数据库时，全部行为必须可测试。

## 2. 负责与不负责

### 2.1 负责

- 维护当前允许命令名到 `CommandHandler` 的注册表；
- 接收 `connection-session` 交付的 `ActiveSession` 与已由 `protocol-core` 校验的 `CommandFrame`；
- 为同一 `generation + command.id` 建立至多一个待完成命令；
- 调用处理器并将成功、拒绝、异常或未知命令转换为关联原始 ID 的 `CommandResultFrame`；
- 仅接受一次处理器完成；迟到或重复完成稳定丢弃；
- 在会话结束时取消该代次全部待完成命令，使迟到完成无法发布；
- 对处理器提供的 detail 执行结果边界校验，不安全内容改为固定脱敏失败消息；
- 通过 `outbound-publisher` 发送结果，由它最终拦截旧代次和串行写入。

### 2.2 明确不负责

- 不解析原始字节、JSON 或命令参数格式；这些属于 `protocol-core` 和具体业务处理器；
- 不生成 `SessionGeneration`、`ActiveSession`、`sessionId`，不判断连接是否 `ACTIVE`；
- 不打开、关闭、重连网络，或直接持有 writer；
- 不执行 DJI 飞行、直播、配对、航线生成或上传；
- 不管理任务传输、KMZ 文件、摘要、路径或临时文件；
- 不创建线程、协程作用域、定时器或重试策略；处理器选择自己的业务执行模型；
- 不重放、持久化或在重连后恢复未完成命令；
- 不把处理器异常、堆栈、路径、原始参数、凭证或 SDK 对象写入结果。

## 3. 命令目录

v1 只允许注册以下精确命令名：

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

未在目录中的命令永远不能注册。`flight.takeoff`、`flight.land`、`flight.return-home` 和所有 `virtual-stick.*` 均不属于本阶段，必须保持未注册并由分发器稳定拒绝。新增、删除或重命名命令必须先修改根契约和本文。

## 4. 对外接口

具体 Kotlin 名称可以微调，但公开能力必须等价于：

```text
CommandDispatcher.create(resultPublisher)
  -> CommandDispatcher

register(commandName, handler)
  -> Registered | RegistrationRejected

unregister(commandName)
  -> Removed | NotRegistered

dispatch(activeSession, commandFrame)
  -> DispatchAccepted
   | DispatchRejected(UNKNOWN_COMMAND | CAPACITY_EXCEEDED)
   | DuplicateInFlight

cancel(generation, endReason)
  -> Unit
```

`CommandDispatcher` 必须实现 `connection-session` 的 `CommandSessionCleanup`，使会话结束流程无需知道内部待完成命令。

### 4.1 `register` 与 `unregister`

- `register` 只接受 §3 目录中的命令名和非空处理器；
- 当前没有注册时返回 `Registered`；相同或不同处理器重复注册同一名称均返回 `RegistrationRejected`，不得替换原处理器；
- `unregister` 仅移除未来分发的映射；已开始的命令仍持有其启动时的处理器，不会被替换或取消；
- 未注册或已移除的名称返回 `NotRegistered`；
- 注册表修改与 `dispatch` 线性化。一个分发要么完整看到旧处理器，要么完整看到新处理器，绝不看到半更新。

### 4.2 `dispatch`

- 调用方只能传入 `connection-session` 实际交付的 `ActiveSession` 和 `protocol-core` 已校验的 `CommandFrame`；
- 同一 `generation + command.id` 正在待完成时，返回 `DuplicateInFlight`，不调用第二次处理器，也不发送第二个结果；
- 同一代次最多允许 64 个待完成命令。超限返回 `DispatchRejected(CAPACITY_EXCEEDED)` 并尝试发送 `ok=false`、detail 为 `"Too many commands are pending"` 的结果；
- 名称未注册时返回 `DispatchRejected(UNKNOWN_COMMAND)` 并尝试发送 `ok=false`、detail 为 `"Command is not available"` 的结果；
- 找到处理器后先原子登记待完成记录，再在模块锁外调用处理器；处理器可以同步或稍后完成；
- 处理器调用抛出异常时，登记记录以失败完成，尝试发送 `ok=false`、detail 为 `"Command failed"`；异常不得穿出 `dispatch`；
- `DispatchAccepted` 只表示处理器已被调用或已获得一次完成入口，不表示业务成功，也不表示结果已送达电脑；
- 本模块不检查 `ActiveSession.sessionId`，不保存它，也不以它判断新旧会话。最终写入前由 `outbound-publisher` 比较代次。

### 4.3 `CommandHandler` 与完成入口

```text
CommandHandler.handle(command, completion)

completion.succeed(detail)
completion.reject(detail)
```

- `command` 是不可变 `CommandFrame`，处理器可读取 `id`、`name` 与通用 JSON fields，但不得修改它；
- `completion` 只能为该次调用使用，不得跨命令复用；
- 第一次 `succeed` 或 `reject` 获胜，随后任何完成调用安静丢弃；
- `succeed(detail)` 形成 `CommandResultFrame(id, true, detail)`；`reject(detail)` 形成 `CommandResultFrame(id, false, detail)`；
- detail 必须满足 `protocol-core` 对 result detail 的限制：最多 1024 个 Unicode code point，且不含控制字符；空 detail 合法；
- detail 不合法时不回显输入，统一形成 `CommandResultFrame(id, false, "Command result is invalid")`；
- 处理器负责参数语义、业务授权和 DJI 操作；分发器只负责名称与结果关联。

### 4.4 `cancel`

- `connection-session` 在当前代次失效后调用 `cancel(generation, endReason)`；
- 本模块原子移除该代次全部待完成记录。它不调用业务处理器的取消方法，不干预已启动的 DJI 或任务操作；
- 后续迟到完成找不到记录，必须安静丢弃，不构造也不发布结果；
- 其他代次的待完成记录不受影响；没有记录或重复取消是幂等空操作；
- `cancel` 返回后，该代次无法通过现有 completion 发布任何新结果；
- `endReason` 仅用于调用者与测试的会话清理接口一致性，本模块不得把它回显给电脑。

## 5. 顺序、并发和资源上限

- `register`、`unregister`、`dispatch`、`cancel` 和 completion 都是线程安全且线性化的；
- 处理器调用、结果发送和处理器的异步回调均不得在分发器内部锁中执行；
- 不同命令可以并发完成，结果发送顺序由 `outbound-publisher` 线性化；本模块不承诺不同命令的业务完成顺序；
- 同一命令完成最多产生一次结果；处理器异常与 completion 竞争时，先线性化的一次终态结果获胜；
- 待完成记录的上限固定为每个代次 64 条；命令完成、异常、未知命令、容量拒绝和取消都不得泄漏记录；
- 任何异常、重复 completion、旧会话 completion 或发送拒绝都不得阻塞之后的命令分发；
- 本模块不保留后台任务或引用，进程重启后不恢复待完成命令。

## 6. 结果与错误边界

`DispatchResult` 只描述本模块是否接受分发，不包含原始字段、sessionId、端点、异常或 writer：

```text
DispatchAccepted
DispatchRejected { UNKNOWN_COMMAND | CAPACITY_EXCEEDED }
DuplicateInFlight
```

- 未知命令与容量拒绝都尝试通过 `outbound-publisher` 发送关联 ID 的失败结果；若会话已失效，publisher 返回旧会话拒绝，本模块安静结束；
- `outbound-publisher` 的编码或写入拒绝不改变 dispatch 结果，不触发重试或断线；
- `CommandResultFrame` 只携带命令 ID、成功布尔值与受限 detail，不增加私有错误字段；
- 任何诊断只能使用固定分类和脱敏短消息，不能包含原始处理器异常。

## 7. 依赖和可替换性

生产依赖只允许：

```text
Kotlin/JVM 标准库
protocol-core 的 CommandFrame、CommandResultFrame 与不可变 JSON 模型
connection-session 的 ActiveSession、SessionGeneration、CommandSessionCleanup、SessionEndReason
outbound-publisher 的 publish 接口和结构化 PublishResult
```

处理器和结果发送器都通过小接口注入。替换业务处理器、发送实现或内部锁实现时，只要本文行为不变，调用方无需修改。

## 8. 必测矩阵

- 目录内每个命令可注册一次，目录外和 `flight.*` / `virtual-stick.*` 稳定拒绝；
- 重复注册不替换处理器；注销只影响未来分发；
- 同步成功、同步拒绝、异步成功、异步拒绝均产生关联原 ID 的唯一结果；
- 空 detail、1024 code point detail、超长 detail 和控制字符 detail；
- 未知命令稳定失败且不调用任何处理器；
- 同一代次相同 ID 并发分发只调用一次处理器、只产生一个结果；完成后允许新命令再次使用该 ID；
- 同一代次第 65 条待完成命令稳定拒绝，不泄漏待完成记录；
- 处理器抛出异常、重复 completion、多个线程同时完成同一命令，最多发送一个脱敏结果；
- 会话取消前后的 completion：取消前已完成可发送，取消后迟到完成不发送；取消一个代次不影响另一代次；
- publisher 拒绝旧代次、编码失败或写入失败时，分发器保持可用且不重试；
- 注册、分发、注销、取消和 completion 并发时无死锁、无重复结果、无超过上限记录；
- main 源码依赖扫描不得出现 Android、DJI、WebSocket、OkHttp、Ktor、文件系统、JSON 第三方类型或任务传输状态。

## 9. 验收标准

只有全部条件同时成立，状态才可改为“已批准并实现”：

1. 模块只承担命令名映射、待完成关联与结果交接；
2. 所有结果都从原始 command ID 构造，并经 `outbound-publisher` 发出；
3. `connection-session` 可以只通过 `CommandSessionCleanup.cancel` 失效旧会话的命令结果；
4. 处理器、发送器和并发回调无法泄漏异常、旧结果或重复结果；
5. 本契约必测矩阵、模块测试和全仓库测试全部零失败、零错误、零跳过；
6. 依赖与架构扫描无越界项，`git diff --check` 无错误。

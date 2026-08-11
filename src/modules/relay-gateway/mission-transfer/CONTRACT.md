# relay-gateway.mission-transfer 模块契约

状态：已批准并已实现
版本：1.0.0
所属一级模块：`relay-gateway`
Gradle 路径：`:relay-gateway:mission-transfer`

本文件是本模块唯一的契约、使用说明、对外接口说明、行为规范和验收依据；实现不得新增改变以下规则的第二份设计文档。

## 1. 目的与唯一职责

`mission-transfer` 持有一个有效电脑会话内单个任务文件传输的完整性：接收三个任务传输帧，经注入 `MissionSink` 暂存字节，验证声明大小和 SHA-256，并经注入结果发布器向电脑报告终态。

调用方只提供会话、帧、sink 和发布器；不得自行处理分块顺序、字节计数、摘要、替换、取消或异常脱敏。模块负责 `mission-begin`、`mission-chunk`、`mission-complete`，每个会话 generation 最多一个活动传输，按到达顺序追加分块且不在模块内保留完整任务，校验大小/摘要，把已验证 `StagedMission` 交给 sink，在失败/替换/会话结束时中止暂存，并发布脱敏 `MissionResultFrame`。

它不解释 WPMZ/KMZ/航线/DJI 业务语义，不上传或控制 DJI 任务，不创建/暴露手机绝对路径，不持有 WebSocket、OkHttp、Android、DJI、数据库、UI 对象，不维护会话状态/创建 generation，不保留全部字节，也不决定无关命令或遥测顺序。`MissionSink` 是未来 `wayline-mission` 接缝，生产实现可流式写入私有存储但公开结果只暴露抽象可读句柄。

## 2. 对外接口

```text
MissionTransfer(sink, resultPublisher) -> MissionTransfer
accept(activeSession, frame) -> Accepted | Completed(stagedMission) | Rejected(kind) | UnsupportedFrame
abort(generation, reason) -> void

MissionSink.begin(metadata) -> Accepted | Rejected
MissionSink.append(bytes) -> Accepted | Rejected
MissionSink.complete() -> StagedMission | Rejected
MissionSink.abort(reason) -> void
```

构造只接受依赖，不创建 sink、发布器、执行器、文件、网络或 Android 对象。`frame` 只能是 `MissionBeginFrame`、`MissionChunkFrame`、`MissionCompleteFrame`；非任务帧是调用方错误，可返回 `UnsupportedFrame` 但不得改变状态或抛出。每次调用绑定 `ActiveSession.generation`，不同 generation 状态永不共享。`abort` 实现 `MissionSessionCleanup`，幂等地删除该 generation 活动传输并调用 sink abort，不发布结果以免复活旧会话。

`MissionMetadata` 只含 `transferId/fileName/size/sha256`；`StagedMission` 另含抽象 `MissionReadable` 的 `readableByMissionModule`，不得是 String、File、Path、URI 或 Android 类型。传给 sink 的字节必须防御复制；sink 拒绝/细节不得发送给电脑。

## 3. 传输与结果规则

1. begin 仅在 sink begin 接受后创建传输；同 ID 第二次 begin 保留当前传输并返回 `TRANSFER_ALREADY_ACTIVE`；不同 ID begin 先 abort 旧传输、为旧 ID 发布 `TRANSFER_SUPERSEDED`，再独立尝试新 begin。
2. 无匹配活动传输的 chunk 返回 `TRANSFER_NOT_ACTIVE` 且不调用 sink；匹配 chunk 恰好追加一次，以原始字节长度累计；将超声明大小的 chunk 必须 abort 并返回 `TRANSFER_SIZE_MISMATCH`。
3. complete 要求累计大小精确等于声明大小，然后比较全部原始追加字节的小写 SHA-256；大小不符返回 `TRANSFER_SIZE_MISMATCH`，摘要不符返回 `TRANSFER_CHECKSUM_MISMATCH`，均 abort。
4. sink 成功 complete 产生一个成功 `MissionResultFrame` 和一个 `Completed`；每种终态拒绝对该 ID 最多一个失败结果帧。发布器失败/异常不得改变状态或抛出；sink 异常转为 `TRANSFER_FAILED`、移除活动传输并尽力 abort；abort 后延迟 chunk/complete 不得写入；模块实例内并发调用必须串行化。

拒绝种类只能是 `TRANSFER_NOT_ACTIVE`、`TRANSFER_ALREADY_ACTIVE`、`TRANSFER_SUPERSEDED`、`TRANSFER_SIZE_MISMATCH`、`TRANSFER_CHECKSUM_MISMATCH`、`TRANSFER_FAILED`。协议 core 已校验帧字段、文件名、声明大小、chunk 大小和 SHA-256 格式，本模块只做传输层校验，不重复 JSON/协议解析。

发布接缝为 `publish(activeSession, MissionResultFrame) -> PublishResult`，必须使用提供帧的同一会话；过期会话发布拒绝忽略，不得路由至新会话。安全 detail 分别固定为 `Mission transfer is not active`、`Mission transfer is already active`、`Mission transfer was superseded`、`Mission transfer size does not match`、`Mission transfer checksum does not match`、`Mission transfer failed`。不得发布异常类/消息、手机路径、临时文件名、堆栈或 sink 细节。

## 4. 测试与变更

测试必须覆盖完整 begin/chunk/complete 与精确 sink 字节、成功发布和暂存句柄、无活动传输、同/异 ID begin、ID 不匹配、超量/不足/摘要不符、每种 sink 拒绝/异常、清理取消与延迟帧、generation 隔离、发布器拒绝/异常、并发无重复追加/完成，以及无 Android、DJI、网络、传输、路径、文件依赖的架构限制。新增帧、错误种类、sink 字段或外部可观察顺序前，必须先更新本契约及测试。

# mission-state-store 模块契约

状态：阶段事件与执行状态交接待按此契约修正；版本：2.0.0；所属一级模块：wayline-mission；Gradle 路径：:wayline-mission:mission-state-store

## 唯一职责与接口

本模块是当前航线任务公开事实的唯一所有者：安全文件元数据、上传状态和执行状态。它将事实组合成不可变快照并按序通知只读消费者。它不存储 KMZ 字节、文件路径/句柄、DJI 对象或原始异常，不写文件、不上传/执行任务、不解析命令、不发送网络；文件归 `mission-staging`，DJI 操作归 uploader 和 executor。

```text
MissionStateStore.create(diagnosticSink = no-op) -> MissionStateStore
store.snapshot() -> MissionSnapshot
store.apply(event) -> Applied(snapshot) | IgnoredStale(sourceRevision)
store.markDeviceUnavailable() -> Applied(snapshot)
store.onChanged(listener) -> Registration
```

`MissionSnapshot` 含严格递增 revision、当前暂存文件代际 `missionRevision`（无文件为 null）、严格递增的设备运行代际 `deviceGeneration`、只含文件名/期望大小/SHA-256 的 `MissionMetadata`、上传状态 `NOT_UPLOADED|UPLOADING(0..100)|UPLOADED|FAILED` 和执行状态 `NOT_STARTED|STARTING|EXECUTING|PAUSED|STOPPING|FINISHED|FAILED`。它不包含 `START_POINT_REACHED`，因为该事实是一次性阶段事件，可能与后续航线执行在同一 DJI 回调内连续发生；阶段事实由 `mission-flight-phase` 的独立事件流保存顺序。封闭事件集为 `FileStaged`、`FileCleared`、`UploadChanged`、`ExecutionChanged`，来源分别是 STAGING、UPLOAD、EXECUTION；上传和执行事件必须携带命令提交时捕获的 `deviceGeneration`。

## 提交规则与测试

每个事件的 `sourceRevision` 为正，并按来源独立比较；同源重复/旧事件返回 `IgnoredStale`、不变更不通知。每个接受的 `FileStaged` 创建严格递增 `missionRevision`，原子替换元数据并重置上传/执行；清除重置一切，已空时仍消费 STAGING 版本但不建快照/通知。上传/执行事件只能影响同时匹配当前 `missionRevision` 和 `deviceGeneration` 的任务，替换/清除任务或设备断开前的回调即使版本更新也必须过期。仅首次 `startMission` 成功回调不得写入 `EXECUTING`；它只能使快照停留在 `STARTING`。只有门面收到当前任务的 `ROUTE_EXECUTION_STARTED` 阶段事实，才可提交首次 `ExecutionChanged(EXECUTING)`。暂停后的 `resumeMission` 成功可以恢复 `EXECUTING`，但不得生成或重放两条首次入场阶段事实。`markDeviceUnavailable()` 每次递增设备运行代际；保留暂存文件，但将上传和执行均置为 `FAILED`，从而要求恢复连接后重新上传。没有当前文件不得改变上传/执行；`UPLOADED` 及执行的 STARTING/EXECUTING/PAUSED/STOPPING/FINISHED 都要求存在上传状态为 `UPLOADED` 的文件。失败不含 DJI 异常/路径/实现细节；进度为 0..100；元数据必须是安全基名、正大小、64 字符十六进制 SHA-256。

`apply` 与 `markDeviceUnavailable` 提交后才返回；监听器在锁外按已提交快照版本顺序调用，失败不能影响其他监听器或状态。`unregister` 幂等，返回前等待其他线程在途回调结束，回调内注销不等待自身，之后不得启动排队回调。诊断仅 `LISTENER_FAILURE` 且接收器异常吞没。测试覆盖初态、暂存/替换/清除、进度边界、上传/执行前置条件、启动回调成功仍为 `STARTING`、仅阶段事实可进入 `EXECUTING`、失败、设备断开及断开前旧回调、每个来源旧/重复版本、无效任务代际、输入校验、通知顺序、监听器失败、并发及自注销。

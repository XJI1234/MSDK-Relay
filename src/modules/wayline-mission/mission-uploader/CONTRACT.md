# mission-uploader 模块契约

状态：已实现并已验证；版本：1.0.0；所属一级模块：wayline-mission；Gradle 路径：:wayline-mission:mission-uploader

## 唯一职责与接口

本模块经共享 DJI 操作协调器将当前暂存 KMZ 上传至飞行器，并只在 `mission-state-store` 记录上传进度和终态公开结果。它不暂存/删除文件、不解析 KMZ、不执行或控制任务、不持有设备连接事实，也不暴露 DJI 异常；暂存字节读取器和 DJI 上传适配器均为注入接缝。

```text
MissionUploader.create(stateStore, contentReader, uploadPort, operationCoordinator, timeoutMillis = 30000)
uploader.start() -> Accepted(cancellation) | Rejected(reason)
```

读取器只为当前暂存文件接收安全 `MissionMetadata` 并返回字节；上传端口接收元数据、字节、进度和完成回调。正常情况下完成只调用一次，重复/延迟调用由协调器和 uploader 忽略。接受只表示上传已提交；成功终态进入 `UPLOADED`，失败、超时、取消、读取器失败、适配器失败或提交拒绝进入 `FAILED`。

## 状态、并发、失败与验证

启动要求当前文件及上传状态 `NOT_UPLOADED`/`FAILED`；每个 uploader 最多一个有效上传，第二次返回 `ALREADY_ACTIVE` 且不读取字节/调用 DJI。读取内容前从单个快照读取元数据和 `missionRevision`，提交前记录 `UPLOADING(0)`。进度必须为 0..100，无效适配器进度忽略；每项进度/终态都携带来源版本和 `missionRevision`，新暂存任务与旧回调完全隔离。

模块 JVM 安全，无 Android 生命周期；协调器提供串行化、超时和取消，超时为 1,000..60,000 ms。启动线程安全且最多一次接受；取消后旧适配器回调忽略，完成终态且幂等。调用方必须在接受操作运行时保持读取器和端口可用；结束后 uploader 不保留字节。

失败映射：无任务 `NO_MISSION` 不变；活动上传 `ALREADY_ACTIVE` 不变；内容不可用/读取器抛出 `CONTENT_UNAVAILABLE` 且 `FAILED`；无效超时/协调器拒绝 `OPERATION_REJECTED` 且 `FAILED`；适配器失败/异常为 `FAILED`；超时 `TIMED_OUT`；取消 `CANCELLED`。公开失败只含稳定枚举。测试覆盖成功、0/100 进度、全部失败类别、排队/运行取消、重复完成、取消后延迟进度、任务替换和并发启动。`start(listener = no-op)` 可接受 `UploadTerminalListener`，仅在已接受上传终态且状态更新尝试后恰好调用一次；拒绝不调用，监听器异常隔离。

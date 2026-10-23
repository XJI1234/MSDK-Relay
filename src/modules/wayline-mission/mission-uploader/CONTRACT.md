# mission-uploader 模块契约

状态：已实现并已验证；版本：1.1.0；所属一级模块：wayline-mission；Gradle 路径：:wayline-mission:mission-uploader

## 唯一职责与接口

本模块经航线域 DJI 操作协调器将当前暂存 KMZ 上传至飞行器，并只在 `mission-state-store` 记录上传进度和终态公开结果。它不暂存/删除文件、不解析 KMZ、不执行或控制任务、不持有设备连接事实；暂存字节读取器和 DJI 上传适配器均为注入接缝。DJI 明确拒绝以受限的 `MissionUploadFailure(errorCode, errorDescription)` 传给终态监听器，模块不暴露 DJI 异常对象。

```text
MissionUploader.create(stateStore, contentReader, uploadPort, operationCoordinator, timeoutMillis = 30000)
uploader.start() -> Accepted(cancellation) | Rejected(reason)
```

读取器只为当前暂存文件接收安全 `MissionMetadata` 并打开一次只读输入流；它不得把完整任务作为 `ByteArray` 返回。上传端口分为两个阶段：`prepare(metadata, content)` 必须在本地完整消费输入流、仅写入其私有上传副本而不调用 DJI，并返回可丢弃的已准备上传；`start(progress, completion)` 才是经航线域协调器触达 DJI 的唯一入口。读取器和上传端口都必须以有界缓冲处理内容，不得因合法的 100 MiB 协议任务形成整文件堆内副本。读取后、调用 DJI 前，uploader 必须再次逐字节核对实际读取长度和 SHA-256 与 `MissionMetadata` 完全一致；输入未被完整消费、长度或摘要不一致时必须丢弃已准备副本、不得调用 `start` 或 DJI，任务进入 `FAILED`。正常情况下完成只调用一次，重复/延迟调用由协调器和 uploader 忽略。接受只表示上传已提交；成功终态进入 `UPLOADED`，失败、超时、取消、读取器失败、完整性复核失败、适配器失败或提交拒绝进入 `FAILED`。

## 状态、并发、失败与验证

上传明确使用协调器的 `releaseSlotOnUnconfirmedOutcome() == true`：超时、运行中取消或调用异常结束本次本地等待后，释放航线操作槽位，不取消尚未开始的队列，不以旧请求没有回执为由拒绝后续上传。操作者可再次发起上传，由 DJI 的新回调裁决；模块不得自动重试，也不得把超时、取消或本地释放伪装成 DJI 失败或成功。旧请求的进度和完成仅属于旧请求，不能更新重试的状态或再次完成命令。Android 端仍需保留 DJI 可能读取的旧文件，直到其真实回调或适配器关闭。

启动要求当前文件及上传状态 `NOT_UPLOADED`/`FAILED`；每个 uploader 最多一个有效上传，第二次返回 `ALREADY_ACTIVE` 且不读取字节/调用 DJI。读取内容前从单个快照读取元数据和 `missionRevision`，并先完成长度和摘要复核，随后才记录 `UPLOADING(0)` 并提交 DJI。进度必须为 0..100，无效适配器进度忽略；每项进度/终态都携带来源版本和 `missionRevision`，新暂存任务与旧回调完全隔离。

模块 JVM 安全，无 Android 生命周期；协调器只串行化已准备上传的 DJI 调用，超时为 1,000..60,000 ms。启动线程安全且最多一次接受；取消后旧适配器回调忽略，完成终态且幂等。若协调器在 `start` 前拒绝，uploader 必须丢弃已准备副本；一旦 `start` 已进入适配器，因 DJI 没有上传取消接口，文件生命周期仍由适配器和真实 DJI 回调管理。每次操作还捕获当前 `deviceGeneration`，设备断开后即使同一 KMZ 的旧回调拥有更高来源版本也不能写入状态。调用方必须在接受操作运行时保持读取器和端口可用；结束后 uploader 不保留字节。

失败映射：无任务 `NO_MISSION` 不变；活动上传 `ALREADY_ACTIVE` 不变；内容不可用、读取器抛出或读取字节与暂存元数据不一致均为 `CONTENT_UNAVAILABLE` 且 `FAILED`；无效超时/协调器拒绝 `OPERATION_REJECTED` 且 `FAILED`；上传端口在尝试调用 DJI 时同步抛出的异常不得在本模块转换为 `UploadCompletion.fail()`，而必须传播给航线域 `DjiOperationCoordinator`。协调器按上传的显式释放策略结束本地等待，保留旧回调独立身份，不阻塞后续请求；上游得到的终态为错误详情为 `null` 的 `FAILED`，表示 `INVOCATION_FAILED`，绝不伪装为 DJI `onFailure`。没有 DJI 错误的异步失败同样为 `FAILED` 且终态错误为 `null`；DJI `onFailure` 必须作为受限的 `MissionUploadFailure(errorCode, errorDescription)` 传给 `UploadTerminalListener`；超时 `TIMED_OUT`；取消 `CANCELLED`。公开失败只含稳定枚举和可选的受限 DJI 错误。测试覆盖成功、0/100 进度、全部失败类别、排队/运行取消、重复完成、取消后延迟进度、任务替换、并发启动、无回执/取消/异常后重试、迟到回调与新请求隔离及排队请求继续调度。`start(listener = no-op)` 可接受 `UploadTerminalListener`，仅在已接受上传终态且状态更新尝试后恰好调用一次；拒绝不调用，监听器异常隔离。

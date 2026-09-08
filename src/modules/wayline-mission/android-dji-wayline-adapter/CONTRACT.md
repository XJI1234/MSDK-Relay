# android-dji-wayline-adapter 模块契约

状态：原始航线状态适配已按此契约实现并验证
版本：2.3.0
所属一级模块：wayline-mission
逻辑 Gradle 路径：`:wayline-mission:android-dji-wayline-adapter`

## 唯一职责

本模块是 `MissionUploadPort`、`MissionControlPort` 与 `MissionExecutionSignalSource` 的唯一 DJI MSDK v5 Android 实现。一个进程级实例负责把已暂存 KMZ 输入流安全、分块写入应用缓存，上传给飞行器，保存本次成功上传的精确文件名，提交开始/暂停/继续/停止命令，并将 DJI 的原始航线状态归一化为不含 DJI 类型的信号。上传入口只委托 `SingleWaylineKmzGuard` 做最小的 WPML 航线基数检查；它不生成、修改或完整校验航线内容，不保存公开任务状态，不判断首点到达，不决定超时、取消或并发策略，不处理网关命令，不注册 SDK，不管理权限，也不渲染界面。

## 对外接口

```text
AndroidDjiWaylineAdapter.create(context) -> AndroidDjiWaylineAdapter
adapter as MissionUploadPort
adapter as MissionControlPort
adapter as MissionExecutionSignalSource
adapter.close() -> Unit
```

同一个实例必须同时注入上传器和执行器。只有上传成功终态可替换当前文件名；失败上传不得破坏此前成功文件名。开始和停止使用该精确 basename，缺少成功上传文件名时同步失败；暂停和继续不需要文件名。

输入文件名必须是单一 `.kmz` basename，禁止绝对路径、父目录、分隔符、空白、控制字符和超过 128 个 Unicode 码点的名称；该上限与中继协议一致，保证上传后的任务状态能够回传桌面端。每次上传写入 `cacheDir/dji-waylines/<唯一代次>/<原始文件名>`，上传路径的 basename 必须与后续控制使用的任务名完全一致。写入失败必须删除该代次的目录和部分文件。

输入 KMZ 必须恰好包含一个 `wpmz/waylines.wpml`，且其中恰好包含一个 DJI WPML 命名空间的 `waylineId`。安全文件名检查通过后，`prepare` 可使用固定大小缓冲把 KMZ 暂存于应用私有缓存，但不得保留完整 `ByteArray` 或调用 DJI；`start` 前的 `prepare` 结果可被 uploader 丢弃。`SingleWaylineKmzGuard` 必须在调用 DJI 上传前用 Android `ZipFile` 的中央目录读取该暂存文件，以禁止外部实体的解析器和受限的解压读取量检查这一事实。不得用 Android `ZipInputStream` 顺序扫描，因为某些合法的 `Stored` 条目会被其错误报告为 EOF，造成后续 `waylines.wpml` 不可见。所有条目的声明解压总量和被读取 WPML 的实际解压量均不得超过上限；不合格文件必须在本次调用内删除。它只确认执行对象不歧义，不能替代 DJI 对 WPML 语义、机型适配或飞行安全的完整校验。归档损坏、缺少 WPML、重复 WPML、零条或多条航线均必须在调用 DJI 上传前失败，且不得修改此前成功上传的文件名。

DJI 上传没有取消接口，因此新上传不得删除仍可能被旧上传读取的输入文件；每个上传终态只清理自己的目录，只有最新上传代次的成功终态可以替换当前文件名。上传进度 `Double` 四舍五入并限制到 `0..100`，非有限值忽略。控制提交、关闭和代次变更必须串行化；关闭后迟到控制回调不得完成上层。每个回调至多一次；`close()` 幂等，使全部迟到回调失效并清理所有剩余目录。

上传失败的来源必须保留，不能把本地失败伪装成 DJI 拒绝。适配器必须经 `WaylineAdapterDiagnosticSink` 记录下列封闭事件：`UPLOAD_INPUT_REJECTED`（文件名或单航线 KMZ 检查未通过）、`UPLOAD_FILE_WRITE_FAILED`（应用缓存写入失败）、`UPLOAD_DJI_INVOCATION_FAILED`（`WaypointMissionManager.init()` 或 `pushKMZFileToAircraft(...)` 在回调前同步抛出）、`UPLOAD_DJI_REJECTED`（DJI 已调用 `onFailure(IDJIError)`）和 `CONTROL_DJI_INVOCATION_FAILED`（`startMission`、`pauseMission`、`resumeMission` 或 `stopMission` 在回调前同步抛出）。`UPLOAD_INPUT_REJECTED` 必须携带受限原因码 `UNSAFE_FILE_NAME` 或 `KMZ_GUARD_REJECTED`，以区分传输元数据和本地 WPML 守卫，不得泄露文件名、路径或字节。`UPLOAD_DJI_REJECTED` 必须携带与公开命令结果完全相同、已经 Android 边界归一化的 `djiErrorCode`（最多 128 个 Unicode 码点）和 `djiErrorDescription`（最多 512 个 Unicode 码点）；二者均不得含控制字符、路径、KMZ 字节、堆栈或 DJI 异常对象。诊断只保留这些受限字段或受限的异常类型名和说明，不将异常对象、堆栈、KMZ 字节、绝对路径或 DJI 对象泄漏出模块。直接调用 `WaypointMissionManager` 时的同步异常必须先记录受限诊断、再原样向上抛出；不得调用上传或控制完成回调、不得改变控制代次，也不得删除本次上传文件，因为 DJI 可能已经接受了该调用。该文件只能在真实异步 DJI 回调结算，或在 `close()` 生命周期清理时删除。该诊断不改变公开命令结果：只有 `onFailure(IDJIError)` 才是带 DJI 错误码与说明的 `ACTION_REJECTED`；同步调用异常和其它没有 DJI 错误的失败均是“DJI 未报告结果”的调用失败，并由上层航线域协调器隔离未确认的写操作。

`MissionExecutionSignalSource` 同时发布归一化后的封闭信号集 `PREPARING|ENTER_WAYLINE|EXECUTING|PAUSED|COMPLETED|INTERRUPTED|IDLE|DISCONNECTED|UNKNOWN` 和受限的原始观察 `MissionExecutionRawState`。原始观察一对一保存官方 `WaypointMissionExecuteStateListener` 的枚举名称：`IDLE|READY|UPLOADING|PREPARING|RECOVERING|ENTER_WAYLINE|EXECUTING|PAUSED|INTERRUPTED|FINISHED|RETURN_TO_START_POINT|DISCONNECTED|NOT_SUPPORTED|UNKNOWN`。它不泄露 DJI 枚举对象或异常，也不得压缩 `RETURN_TO_START_POINT`、`READY`、`UPLOADING`、`RECOVERING`、`PAUSED` 或 `NOT_SUPPORTED`。监听必须在 `startMission` 调用前完成注册，关闭时必须取消注册；关闭、任务替换或设备代际失效后的回调不得投递。适配器不得把 `startMission` 成功回调转换为 `ENTER_WAYLINE` 或 `EXECUTING`，也不得从遥测位置推测信号。

DJI 原始状态不携带本项目任务身份，因此适配器必须实现 `beginStartAttempt`、`confirmStartAttempt` 和 `invalidateStartAttempt` 状态隔离：门面准备启动新任务时先关闭投递，监听注册和 `startMission` 调用期间的任何状态都丢弃；仅在同一启动请求收到 DJI 成功回执后才打开投递。回执失败、超时、取消、停止、任务替换、设备失效或关闭时再次关闭。禁止缓存或补发被隔离状态，避免把上一任务的迟到回调归属给新任务。

原始 `RETURN_TO_START_POINT` 表示飞行器仍在执行返航动作，不是任务完成；它必须保持为非终态 `EXECUTING`，直到 DJI 后续明确报告 `FINISHED`。只有 `FINISHED` 可以映射为 `COMPLETED`。对于 `pushKMZFileToAircraft`、`startMission`、`pauseMission`、`resumeMission` 和 `stopMission`，DJI `onFailure(IDJIError)` 的 `errorCode()` 与 `description()` 必须在 Android 边界归一化为受限的错误码和描述，并经对应端口原样传回；同步异常、关闭和没有可用 `IDJIError` 的失败只产生无错误详情的失败。适配器绝不把 `IDJIError` 实例越过该模块边界。`close()` 必须最终调用 `WaypointMissionManager.destroy()`，以取消其产品类型监听并销毁内部任务操作者；上层适配器负责隔离该调用异常。

模块仅依赖 `mission-uploader`、`mission-executor`、`mission-flight-phase` 和 DJI MSDK v5.17。JVM 测试覆盖文件名防穿越、零条和多条 WPML 航线的提前拒绝、写入失败、进度、成功/失败、重复和迟到回调、当前文件名替换规则、四种控制、同步异常、关闭与清理、DJI 状态到封闭信号集的完整映射、返航中不提前完成、监听先注册、关闭后静默及禁止从启动回调伪造阶段。Android Debug 构建必须编译真实 `IWaypointMissionManager`；真机仍需验证 `init()`、KMZ 上传、文件名匹配、飞行器控制以及 `ENTER_WAYLINE` 到 `EXECUTING` 的状态回调顺序。

# mission-executor 模块契约

状态：启动阶段语义已按此契约实现并验证；版本：2.3.0；所属一级模块：wayline-mission；Gradle 路径：:wayline-mission:mission-executor

## 唯一职责、接口与前置条件

本模块只提交已上传航线任务的启动、暂停、继续和停止命令，并经航线域 DJI 操作协调器串行化命令及记录命令终态。它不判定飞行器是否已起飞、已到达首航点或已进入航线；这些事实只属于 `mission-flight-phase`。它不上传、暂存、解析、规划、连接设备，也不暴露 DJI 对象或异常；注入的 `MissionControlPort` 是唯一 DJI 命令接缝。

`executor.start()/pause()/resume()/stop() -> Accepted(cancellation) | Rejected(reason)`。

启动要求当前文件且上传状态 `UPLOADED`。本地执行状态不得阻止再次调用 `startMission`；同一启动命令仍在等待本次 DJI 回执时不得叠发。这些是指定本次任务文件和防止同一次调用重入的业务不变量；它们不替代 DJI 的设备安全校验。门面不得在调用 `startMission` 前以 SDK、遥控器、飞机、飞控、航线能力、电量、`isFlying` 或 `motorsOn` 作本地设备安全裁决。桌面端负责在发送前确认中继与 MSDK 已知可达；从手机实际调用 `startMission` 起，DJI 的 `onSuccess`/`onFailure(IDJIError)` 是唯一设备接受与拒绝依据。对频是连接新飞机或更换遥控器时的独立维护操作，不属于启动航线的条件。暂停要求经阶段模块确认的 `EXECUTING`；继续要求 `PAUSED`；停止要求 `STARTING`/`EXECUTING`/`PAUSED`，或前一次停止结果未确认而保持的 `STOPPING`。同时最多一个控制操作。接受只表示提交：启动提交前记录 `STARTING`，`startMission` 成功后只完成启动命令，保持 `STARTING`，并由组合层确认 `mission-flight-phase`；只有门面接收阶段模块的 `ROUTE_EXECUTION_STARTED` 后才能原子写入 `EXECUTING`。暂停成功后 `PAUSED`；继续命令成功后恢复为 `EXECUTING`，但它不是新的首航点进入，绝不产生 `START_POINT_REACHED` 或 `ROUTE_EXECUTION_STARTED`；停止提交前 `STOPPING`、成功后 `FINISHED`。

启动命令在已接受后收到带有 DJI 错误的 `onFailure`，表示本次调用被 DJI 明确拒绝，必须恢复调用前执行状态（通常为 `NOT_STARTED`），将错误传给终态监听器并允许重新尝试。端口在尝试调用 DJI 时同步抛出的异常不得由本模块转换为 `ControlCompletion.fail()`，而必须原样传播给航线域 `DjiOperationCoordinator`；在调用边界已开始后，这不能证明 DJI 没有收到命令，协调器必须向上游报告无 DJI 错误详情的 `FAILED`（即 `INVOCATION_FAILED`），并继续隔离航线控制域。同步调用异常、没有 DJI 错误的失败、超时或取消不能证明 DJI 没有执行，因此必须保持 `STARTING`；操作员可以再次发送启动，由 DJI 回调裁决。超时后的再次启动必须作为同一任务身份的等价重试进入航线协调器，以便真正再次调用 `startMission`。暂停或继续超时后的启动必须声明为协调器的 `SUPERSEDE_UNCONFIRMED`：它只替换已经隔离的暂停/恢复槽位，不得抢占仍在等待本次 DJI 回执的暂停或恢复。暂停或继续的明确 DJI 失败必须恢复命令前状态；只有超时、取消、同步调用异常或丢失回执时才进入回执不确定状态。用于解除不确定状态的匹配 DJI 状态必须来自同一任务代际、同一设备代次，且严格晚于该次 DJI 调用的本地调用边界；调用前已经存在的目标状态不得解除不确定状态。后到的超时不得重新加锁。停止是单向收尾操作：在航线域协调器隔离本航线写操作的结果不确定时，它可作为明确的 containment 操作替换该未确认调用，仍完全由 DJI 的实际回调裁决；在同一任务代际和设备代次的停止重试还带私有等价标记。启动在暂停或恢复已经超时隔离后，可作为 `SUPERSEDE_UNCONFIRMED` 替换该槽位并再次调用 `startMission`；此例外不得用于仍在等待回执的在途暂停或恢复，也不得用于暂停或继续自身。暂停、继续不得作为 containment。停止在超时、取消或同步调用异常时保持 `STOPPING`；明确失败或提交被拒绝时，首次停止恢复命令前状态；从 `STOPPING` 重试后的失败保持 `STOPPING`，以免把未确认的设备状态伪装为仍在执行。来自 MSDK `onFailure` 的受限错误码和描述必须作为可选 `MissionControlFailure` 传给终态监听器；同步调用异常或没有 MSDK 错误的失败传 `null`。所有这些请求仍只向调用者报告一次安全终态，绝不把不确定性伪装成成功或失败。

当一个普通 `START`、`PAUSE` 或 `RESUME` 已被接受且仍等待 DJI 回执时，明确的 `STOP` 是唯一可替换它的收尾意图。旧请求必须恰好收到一次 `CANCELLED`，其后的回调不得修改任务状态或再次通知；新停止请求先写入 `STOPPING`，再经航线域协调器的 containment 规则尝试送达 DJI。正在进行的 `STOP` 不得被第二个停止重入；只有其结果未确认且任务仍为 `STOPPING` 时才可按既有等价重试规则再次尝试。这是对“同时最多一个控制操作”的受限安全例外，不增加任何本地设备安全门禁。

## 生命周期、失败与测试

模块 JVM 安全。协调器持有操作串行化、超时、取消、重复完成抑制和回调顺序；执行器还阻止自身普通命令重叠，并按上文只允许收尾停止替换普通在途命令。每个回调绑定命令开始时捕获的 `missionRevision` 和 `deviceGeneration`，替换/清除任务或设备断开均使旧回调无害；每个已接受请求都必须恰好收到一次终态，只有当前请求可迁移任务状态；超时必须为 1,000..60,000 ms。

公开拒绝仅为 `NO_MISSION`、`NOT_UPLOADED`、`INVALID_STATE`、`ALREADY_ACTIVE`、`OPERATION_UNCONFIRMED`、`OPERATION_REJECTED`，不得含异常、路径、DJI 对象或字节内容。`onFailure(IDJIError)` 不得被压缩为上述本地拒绝，必须携带受限的 DJI 错误码和说明交给上层统一回传。控制操作没有进度，故不存在无效适配器进度。

JVM 测试必须覆盖四个命令、有效迁移、全部前置条件拒绝、启动成功仍保持 `STARTING`、全部适配器失败/异常、协调器拒绝、超时、取消、重复完成、任务替换后的旧回调和并发命令。每个请求可选 `ExecutionTerminalListener`：仅已接受操作在相应状态更新尝试后恰好收到一次 `SUCCEEDED|FAILED|TIMED_OUT|CANCELLED`；前置条件/提交拒绝不调用监听器，监听器失败必须隔离。

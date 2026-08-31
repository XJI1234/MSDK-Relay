# mission-executor 模块契约

状态：启动阶段语义已按此契约实现并验证；版本：2.0.0；所属一级模块：wayline-mission；Gradle 路径：:wayline-mission:mission-executor

## 唯一职责、接口与前置条件

本模块只提交已上传航线任务的启动、暂停、继续和停止命令，并经共享 DJI 操作协调器串行化命令及记录命令终态。它不判定飞行器是否已起飞、已到达首航点或已进入航线；这些事实只属于 `mission-flight-phase`。它不上传、暂存、解析、规划、连接设备，也不暴露 DJI 对象或异常；注入的 `MissionControlPort` 是唯一 DJI 命令接缝。

`executor.start()/pause()/resume()/stop() -> Accepted(cancellation) | Rejected(reason)`。

启动要求当前文件、上传状态 `UPLOADED` 和执行状态 `NOT_STARTED`/`FAILED`，并且注入的 `MissionStartSafetyGate` 必须明确允许启动；门面必须让该门禁同时核对 SDK、遥控器、飞机、飞控、航线能力、电量、`isFlying == false` 和 `motorsOn == false`。对频是连接新飞机或更换遥控器时的独立维护操作，不属于启动航线的条件。任一必要事实未知、门禁异常或拒绝均不得调用 `startMission`。暂停要求经阶段模块确认的 `EXECUTING`；继续要求 `PAUSED`；停止要求 `STARTING`/`EXECUTING`/`PAUSED`。同时最多一个控制操作。接受只表示提交：启动提交前记录 `STARTING`，`startMission` 成功后只完成启动命令，保持 `STARTING`，并由组合层确认 `mission-flight-phase`；只有门面接收阶段模块的 `ROUTE_EXECUTION_STARTED` 后才能原子写入 `EXECUTING`。暂停成功后 `PAUSED`；继续命令成功后恢复为 `EXECUTING`，但它不是新的首航点进入，绝不产生 `START_POINT_REACHED` 或 `ROUTE_EXECUTION_STARTED`；停止提交前 `STOPPING`、成功后 `FINISHED`。

启动命令在已接受后收到失败、超时、取消或适配器异常，不能证明 DJI 没有执行，因此必须保持 `STARTING`，禁止重发启动，只允许停止。暂停或继续在回执不确定时保持其已知执行状态；若同一任务代际的匹配 DJI 状态已先到达，则该状态解除不确定性，后到的超时不得重新加锁；否则必须拒绝再次暂停或继续，只允许停止。停止在超时或取消时保持 `STOPPING`，不得重发；明确失败或提交被拒绝时才恢复命令前状态。所有这些请求仍只向调用者报告一次安全终态，绝不把不确定性伪装成成功或失败。

## 生命周期、失败与测试

模块 JVM 安全。协调器持有操作串行化、超时、取消、重复完成抑制和回调顺序；执行器还阻止自身命令重叠。每个回调绑定命令开始时捕获的 `missionRevision` 和 `deviceGeneration`，替换/清除任务或设备断开均使旧回调无害；回调终态且幂等；超时必须为 1,000..60,000 ms。

公开拒绝仅为 `NO_MISSION`、`NOT_UPLOADED`、`INVALID_STATE`、`ALREADY_ACTIVE`、`OPERATION_UNCONFIRMED`、`SAFETY_CHECK_FAILED`、`OPERATION_REJECTED`，不得含异常、路径、DJI 对象或字节内容。控制操作没有进度，故不存在无效适配器进度。

JVM 测试必须覆盖四个命令、有效迁移、全部前置条件拒绝、启动成功仍保持 `STARTING`、全部适配器失败/异常、协调器拒绝、超时、取消、重复完成、任务替换后的旧回调和并发命令。每个请求可选 `ExecutionTerminalListener`：仅已接受操作在相应状态更新尝试后恰好收到一次 `SUCCEEDED|FAILED|TIMED_OUT|CANCELLED`；前置条件/提交拒绝不调用监听器，监听器失败必须隔离。

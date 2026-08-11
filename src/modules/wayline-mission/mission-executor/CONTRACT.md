# mission-executor 模块契约

状态：已实现并已验证；版本：1.0.0；所属一级模块：wayline-mission；Gradle 路径：:wayline-mission:mission-executor

## 唯一职责、接口与前置条件

本模块控制已上传航线任务的启动、暂停、继续和停止，经共享 DJI 操作协调器串行化每项控制操作，并在 `mission-state-store` 记录公开执行状态。它不上传、暂存、解析、规划、连接设备，也不暴露 DJI 对象或异常；注入的 `MissionControlPort` 是唯一 DJI 操作接缝。

`executor.start()/pause()/resume()/stop() -> Accepted(cancellation) | Rejected(reason)`。

启动要求当前文件、上传状态 `UPLOADED` 和执行状态 `NOT_STARTED`/`FAILED`；暂停要求 `EXECUTING`；继续要求 `PAUSED`；停止要求 `STARTING`/`EXECUTING`/`PAUSED`。同时最多一个控制操作。接受只表示提交：启动提交前记录 `STARTING`、成功后 `EXECUTING`；暂停成功后 `PAUSED`；继续成功后 `EXECUTING`；停止提交前 `STOPPING`、成功后 `FINISHED`。操作失败、超时、取消、适配器异常或协调器拒绝均记录 `FAILED`，仅前置条件拒绝保持原状态。

## 生命周期、失败与测试

模块 JVM 安全。协调器持有操作串行化、超时、取消、重复完成抑制和回调顺序；执行器还阻止自身命令重叠。每个回调绑定命令开始时捕获的 `missionRevision`，替换/清除任务使旧回调无害；回调终态且幂等；超时必须为 1,000..60,000 ms。

公开拒绝仅为 `NO_MISSION`、`NOT_UPLOADED`、`INVALID_STATE`、`ALREADY_ACTIVE`、`OPERATION_REJECTED`，不得含异常、路径、DJI 对象或字节内容。控制操作没有进度，故不存在无效适配器进度。

JVM 测试必须覆盖四个命令、有效迁移、全部前置条件拒绝、适配器失败/异常、协调器拒绝、超时、取消、重复完成、任务替换后的旧回调和并发命令。每个请求可选 `ExecutionTerminalListener`：仅已接受操作在相应状态更新尝试后恰好收到一次 `SUCCEEDED|FAILED|TIMED_OUT|CANCELLED`；前置条件/提交拒绝不调用监听器，监听器失败必须隔离。

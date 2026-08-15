# mission-flight-phase 模块契约

状态：已实现并验证；版本：1.0.0；所属一级模块：wayline-mission；Gradle 路径：`:wayline-mission:mission-flight-phase`

## 唯一职责

本模块只把 DJI MSDK 的原始航线任务状态转换为可供电脑端消费的、可信且有序的阶段事实。它回答两个问题：飞行器是否已经由 DJI 确认进入首航点，以及航线是否已经由 DJI 确认开始执行。

它不调用 `startMission`、不控制起飞、不操纵虚拟摇杆、不估算位置、不生成或上传 KMZ、不保存文件、不发送 WebSocket、不决定任务超时，也不暴露 DJI 枚举、异常、位置或路径。

## 对外接口

```text
MissionFlightPhase.create(sink, diagnosticSink?) -> MissionFlightPhase
phaseTracker.arm(missionRevision, deviceGeneration, fileName) -> Unit
phaseTracker.accept(signal, missionRevision, deviceGeneration) -> Accepted | IgnoredStale
phaseTracker.invalidate(missionRevision?, deviceGeneration) -> Unit
```

`sink` 只能是一级模块门面提供的阶段事件入口，不能是 WebSocket、协议帧写入器或电脑端回调。`arm` 只能由已提交 `wayline.start` 的当前任务调用一次，并接收当前任务的 1..128 个 Unicode 码点安全 `.kmz` 基名；该上限与中继协议一致。`accept` 的输入是经过 Android DJI 适配器归一化后的封闭信号集：`PREPARING`、`ENTER_WAYLINE`、`EXECUTING`、`PAUSED`、`COMPLETED`、`INTERRUPTED`、`IDLE`、`DISCONNECTED` 与 `UNKNOWN`。输入必须携带任务代际和设备运行代际；不匹配当前已武装任务的输入返回 `IgnoredStale`，不通知、不诊断。

输出的阶段事实是：

```text
START_POINT_REACHED
ROUTE_EXECUTION_STARTED
```

每条事实都必须带当前 `missionRevision`、`deviceGeneration`、严格递增的阶段序号及安全文件名；不得携带 DJI 原始状态、异常、坐标、文件路径、KMZ 字节或敏感诊断内容。阶段序号在一个 `missionRevision` 内从 1 开始，且只能递增；任务替换后重新从 1 开始。

## 判定规则

1. `ENTER_WAYLINE` 是唯一允许产生 `START_POINT_REACHED` 的信号。它只提交该事实一次，不得在同一次信号中合成 `ROUTE_EXECUTION_STARTED`。模块不自行写 `MissionStateStore`；门面只在收到 `ROUTE_EXECUTION_STARTED` 时把当前任务更新为 `EXECUTING`。
2. `ENTER_WAYLINE` 重复、`EXECUTING` 重复及其他无关状态不得重发已提交事实。
3. 已收到 `ENTER_WAYLINE` 后的首次 `EXECUTING` 提交 `ROUTE_EXECUTION_STARTED`，且不得附带 `ENTRY_STATE_MISSING`。之后的 `EXECUTING` 只确认持续执行，不产生额外事实。两条事实可以紧挨着到达（DJI 先后发出两个信号），但不得由一次 `ENTER_WAYLINE` 同时合成。阶段序号必须连续且顺序不可逆。
4. 未收到 `ENTER_WAYLINE` 而先收到 `EXECUTING` 时，模块只提交 `ROUTE_EXECUTION_STARTED`，并向可选诊断接收器提交 `ENTRY_STATE_MISSING`。它永远不得凭时间、遥测位置或航点距离补造 `START_POINT_REACHED`。
5. `PREPARING` 不是首点到达；`startMission` 成功回调也不是首点到达或航线开始。二者均不产生阶段事实。
6. `PAUSED`、`COMPLETED`、`INTERRUPTED`、`IDLE`、`DISCONNECTED`、`UNKNOWN` 不产生本模块的两条正向事实。门面可仅在 `accept` 返回 `Accepted` 后依据这些已验证属于当前任务的信号更新自己的任务状态；本模块绝不自行写状态。停止、失败、设备断开、任务替换和 `invalidate` 后，旧回调必须无害。

## 生命周期、失败和测试

模块 JVM 安全。`sink` 在锁外被顺序调用；接收器异常必须隔离并仅写 `PHASE_SINK_FAILURE` 诊断，不得回滚已提交事实或阻塞后续阶段。诊断不可含路径、坐标、任务内容、设备标识、URL、认证信息或原始异常。

JVM 契约测试必须覆盖：`ENTER_WAYLINE` 只产生首点事实、随后首次 `EXECUTING` 才产生开始执行事实且无缺失入场诊断、连续阶段序号、每种重复信号、`EXECUTING` 缺失入场信号、`PREPARING` 与启动回调不产生事实、任务替换、设备断开、失效后迟到回调、并发输入、接收器异常不得丢掉后续 `EXECUTING` 事实、诊断脱敏和无 DJI/Android 依赖。

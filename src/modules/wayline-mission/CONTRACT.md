# wayline-mission 一级模块契约

状态：航线入场阶段已实现并通过 JVM、Android 单元测试与全仓回归验证
版本：2.0.0
所属程序：MSDK Relay Android
Gradle 路径：`:wayline-mission`

## 1. 唯一职责

`wayline-mission` 负责手机端航线文件从安全暂存到 DJI 航线任务执行的设备侧流程。它不规划地图、不替电脑端编辑航点、不管理 WebSocket、不拥有设备连接事实。

## 2. 二级模块

| 二级模块 | 唯一职责 |
| --- | --- |
| `mission-staging` | 接收完整 KMZ 字节、校验摘要、原子暂存和清理 |
| `wpmz-generator` | 将电脑端给出的航点计划转换为 DJI WPMZ/KMZ |
| `mission-state-store` | 保存文件元数据、上传进度和执行状态 |
| `mission-uploader` | 将当前暂存航线上传至设备 |
| `mission-executor` | 只提交开始、暂停、恢复和停止任务命令，并管理命令终态 |
| `mission-flight-phase` | 将 DJI 航线状态转换为可信的入场与执行阶段事实 |
| `wayline-command-handler` | 解释 wayline 命令并调用对应能力 |
| `android-dji-wayline-adapter` | 将 KMZ 字节、上传进度、任务控制及原始任务状态转换为 DJI MSDK v5 操作 |
| `android-mission-staging-adapter` | 在应用私有目录原子暂存并读取当前 KMZ 文件 |

所有 DJI 操作必须通过 `device-connection` 的统一操作调度入口；文件字节只由 `mission-staging` 拥有。

## 3. 不变量

- 文件暂存成功不代表已上传，上传成功不代表任务已开始。
- `wayline.start` 只有一个 DJI 飞控命令：DJI 负责起飞、按 KMZ 的入场策略飞往首航点并连续执行航线。手机端不得把它拆成二次起飞、虚拟摇杆导航或第二次 `startMission` 调用。
- `startMission` 的成功回调只表示 DJI 接受了启动请求，绝不表示飞行器已到达首航点或已开始飞行航线。
- 仅 DJI 的 `ENTER_WAYLINE` 原始状态可确认飞行器已进入首航点。收到该状态时，必须按顺序产生 `START_POINT_REACHED`、再产生 `ROUTE_EXECUTION_STARTED` 两条阶段事实；二者不得乱序、重复或合并为一条。
- 没有收到 `ENTER_WAYLINE` 而直接收到 `EXECUTING` 时，只能确认 `ROUTE_EXECUTION_STARTED`，不得补造 `START_POINT_REACHED`；必须记录不含敏感数据的 `ENTRY_STATE_MISSING` 诊断。
- 任何基于时间、距离、手机定位或预估速度的首航点到达判断都是禁止的。设备断开、任务替换、停止、失败或过期回调后，旧任务不得产生阶段事实。
- 同时只能存在一个活动暂存写入；失败、取消和重启必须清理半成品。
- 所有对外结果不包含 Android 绝对路径、临时文件名、原始异常或文件全部内容。

## 4. 门面对外接口与组合职责

```text
WaylineMission.create(dependencies) -> WaylineMission
mission.commandHandler() -> CommandHandler
mission.missionSink() -> MissionSink
mission.snapshot() -> MissionSnapshot
mission.onChanged(listener) -> Registration
mission.onPhaseChanged(listener) -> Registration
mission.markDeviceUnavailable() -> MissionSnapshot
```

`MissionSnapshot` 是可替换的当前任务状态，不能承载可能在同一 DJI 回调内连续发生的两个阶段事实。`onPhaseChanged` 是单独的、有序且仅追加的阶段事件流；每项事件包含安全文件名、任务代际、设备代际、阶段序号与阶段种类。它只向应用组合层交付，不直接编码或发送网络帧。

`WaylineMission` 只组合 `MissionStaging`、`MissionStateStore`、`MissionUploader`、`MissionExecutor`、`MissionFlightPhase` 和 `WaylineCommandHandler`。它独占“暂存成功后写入任务状态”的原子交接，并为生成命令与 gateway 文件传输共用同一暂存锁；它将阶段事实按既定顺序交给应用组合层，但不解析 WebSocket、不连接 DJI、不规划航点、不暴露路径或 DJI 对象。

阶段交接顺序固定如下：先由 `mission-flight-phase` 产生 `START_POINT_REACHED`，门面在该事实已进入阶段事件流后，才处理 `ROUTE_EXECUTION_STARTED`；处理后者时，门面先把当前任务状态原子更新为 `EXECUTING`，再将 `ROUTE_EXECUTION_STARTED` 放入阶段事件流。应用层必须按阶段序号将两条事件映射为两条独立的电脑端上报；上报失败不能回滚任务状态或重新产生阶段事实，只能记录受限诊断。

成功生成或完整接收的 KMZ 必须先安全暂存、再写入 `FileStaged` 状态、最后才向 gateway 报告成功。上传和控制操作的接受仅表示已提交；只有对应 DJI 终态成功后才报告成功。每个中继命令最多完成一次，旧任务、重复、取消、超时或延迟回调不得改变新任务状态或重新完成命令。

依赖只包含 `StagingStorage`、当前文件内容读取器、上传端口、控制端口、原始 DJI 任务状态源、共享 `DjiOperationCoordinator`、合法范围的超时和可选状态诊断接收器。门面不拥有或关闭注入的适配器与协调器。

`markDeviceUnavailable()` 是设备连接生命周期唯一调用的安全入口。它保留已安全暂存的 KMZ，取消全部已接受但尚未终态的上传和控制操作，并使当前设备侧上传与执行事实进入 `FAILED`；恢复连接后必须重新上传，不能把断开前的 `UPLOADED` 当作仍然有效。该动作建立新的设备运行代际，因此断开前的上传进度、DJI 成功、失败、超时或取消回调均不得改变当前快照，也不得将已拒绝的 relay 命令重新报告为成功。门面在同一生命周期锁内提交操作、追踪取消句柄和失效设备，避免断开与新命令交错时遗漏取消；重复调用幂等地维持安全状态。

## 5. 验证要求

二级模块必须各自拥有中文 `CONTRACT.md`、独立 JVM 契约测试和严格单一职责。一级模块测试必须覆盖生成与传输暂存、状态写入先于 gateway 成功、上传/控制成功与失败、超时/取消、设备断开后的旧成功回调、任务替换后的旧回调、重复完成、传输中止和并发暂存竞争；还必须覆盖启动回调不得直接进入航线执行、`ENTER_WAYLINE` 的两条有序上报、缺失入场状态时禁止伪造首点到达、重复/迟到/断开后的原始状态和网关上报失败隔离。全仓回归成功后方可标为已验证。

# wayline-mission 一级模块契约

状态：已实施并已验证
版本：1.1.0
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
| `mission-executor` | 开始、暂停、恢复和停止任务 |
| `wayline-command-handler` | 解释 wayline 命令并调用对应能力 |
| `android-dji-wayline-adapter` | 将 KMZ 字节、上传进度和任务控制转换为 DJI MSDK v5 操作 |
| `android-mission-staging-adapter` | 在应用私有目录原子暂存并读取当前 KMZ 文件 |

所有 DJI 操作必须通过 `device-connection` 的统一操作调度入口；文件字节只由 `mission-staging` 拥有。

## 3. 不变量

- 文件暂存成功不代表已上传，上传成功不代表任务已开始。
- 同时只能存在一个活动暂存写入；失败、取消和重启必须清理半成品。
- 所有对外结果不包含 Android 绝对路径、临时文件名、原始异常或文件全部内容。

## 4. 门面对外接口与组合职责

```text
WaylineMission.create(dependencies) -> WaylineMission
mission.commandHandler() -> CommandHandler
mission.missionSink() -> MissionSink
mission.snapshot() -> MissionSnapshot
mission.onChanged(listener) -> Registration
mission.markDeviceUnavailable() -> MissionSnapshot
```

`WaylineMission` 只组合 `MissionStaging`、`MissionStateStore`、`MissionUploader`、`MissionExecutor` 和 `WaylineCommandHandler`。它独占“暂存成功后写入任务状态”的原子交接，并为生成命令与 gateway 文件传输共用同一暂存锁；它不解析 WebSocket、不连接 DJI、不规划航点、不暴露路径或 DJI 对象。

成功生成或完整接收的 KMZ 必须先安全暂存、再写入 `FileStaged` 状态、最后才向 gateway 报告成功。上传和控制操作的接受仅表示已提交；只有对应 DJI 终态成功后才报告成功。每个中继命令最多完成一次，旧任务、重复、取消、超时或延迟回调不得改变新任务状态或重新完成命令。

依赖只包含 `StagingStorage`、当前文件内容读取器、上传端口、控制端口、共享 `DjiOperationCoordinator`、合法范围的超时和可选状态诊断接收器。门面不拥有或关闭注入的适配器与协调器。

`markDeviceUnavailable()` 是设备连接生命周期唯一调用的安全入口。它保留已安全暂存的 KMZ，取消全部已接受但尚未终态的上传和控制操作，并使当前设备侧上传与执行事实进入 `FAILED`；恢复连接后必须重新上传，不能把断开前的 `UPLOADED` 当作仍然有效。该动作建立新的设备运行代际，因此断开前的上传进度、DJI 成功、失败、超时或取消回调均不得改变当前快照，也不得将已拒绝的 relay 命令重新报告为成功。门面在同一生命周期锁内提交操作、追踪取消句柄和失效设备，避免断开与新命令交错时遗漏取消；重复调用幂等地维持安全状态。

## 5. 验证要求

二级模块必须各自拥有中文 `CONTRACT.md`、独立 JVM 契约测试和严格单一职责。一级模块测试必须覆盖生成与传输暂存、状态写入先于 gateway 成功、上传/控制成功与失败、超时/取消、设备断开后的旧成功回调、任务替换后的旧回调、重复完成、传输中止和并发暂存竞争。全仓回归成功后方可标为已验证。

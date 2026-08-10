# wayline-mission 一级模块契约

状态：按二级模块逐个实施
版本：1.0.0
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

所有 DJI 操作必须通过 `device-connection` 的统一操作调度入口；文件字节只由 `mission-staging` 拥有。

## 3. 不变量

- 文件暂存成功不代表已上传，上传成功不代表任务已开始。
- 同时只能存在一个活动暂存写入；失败、取消和重启必须清理半成品。
- 所有对外结果不包含 Android 绝对路径、临时文件名、原始异常或文件全部内容。

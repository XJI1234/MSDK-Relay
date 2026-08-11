# live-stream 模块契约

状态：已实现并已验证
版本：1.0.0
Gradle 路径：:live-stream

## 1. 唯一职责

`live-stream` 持有 RTMP 图传配置、DJI 图传启动/停止协调，以及向遥测暴露的不可变图传状态。它不接收、转码、播放或存储视频；视频路径始终是 DJI SDK 到电脑的 RTMP 服务。

二级模块：`stream-config-validator` 校验 RTMP 配置；`dji-stream-adapter` 经共享操作协调器适配 DJI 操作；`stream-state-store` 持有图传状态和指标；`stream-command-handler` 解释 `live-stream.start`/`live-stream.stop` 并将结果映射为中继安全结果。

## 2. 所有权与行为规则

只有 `stream-state-store` 持有图传事实，只有 `dji-stream-adapter` 可以调用 DJI 图传方法，所有 DJI 调用都经 `device-connection` 的 `dji-operation-coordinator`。命令处理器和校验器均不持有状态。

`live-stream.start` 必须在任何 DJI 调用前校验 RTMP URL。只有 DJI 确认启动成功后才报告图传活动。停止、启动失败、超时、取消和设备断开必须产生稳定的非活动状态和安全提示。重复或延迟 DJI 回调不得改变较新的状态，也不得完成同一中继命令两次。公开结果不得包含密码、令牌、文件路径、原始异常或 DJI 对象。

每个二级模块必须有自己的 `CONTRACT.md`、纯规则及适配器边界的 JVM 测试，并在组合父门面前具备真实 DJI 设备验证。

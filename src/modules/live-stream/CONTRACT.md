# live-stream 模块契约

状态：已实施并已验证
版本：1.1.0
Gradle 路径：:live-stream

## 1. 唯一职责

`live-stream` 持有 RTMP 图传配置、DJI 图传启动/停止协调，以及向遥测暴露的不可变图传状态。它不接收、转码、播放或存储视频；视频路径始终是 DJI SDK 到电脑的 RTMP 服务。

二级模块：`stream-config-validator` 校验 RTMP 配置；`dji-stream-adapter` 经共享操作协调器适配 DJI 操作；`android-dji-stream-adapter` 实现真实 DJI MSDK v5 RTMP 接口；`stream-state-store` 持有图传状态和指标；`stream-command-handler` 解释 `live-stream.start`/`live-stream.stop` 并将结果映射为中继安全结果。

`LiveStream` 门面只组合上述二级模块，并向 gateway 暴露命令处理器、不可变图传快照和状态订阅。它不接收、转码、播放或存储视频，不建立 RTMP Socket，不直接调用 DJI SDK，也不保存第二份图传状态。设备不可用时，它只通过已注入的 `DjiStreamPort.stop` 停止残留 RTMP。

## 2. 对外接口

```text
LiveStream.create(dependencies) -> LiveStream
liveStream.commandHandler() -> CommandHandler
liveStream.snapshot() -> StreamSnapshot
liveStream.onChanged(listener) -> Registration
liveStream.markDeviceUnavailable() -> StreamSnapshot
```

`LiveStreamDependencies` 接受 `DjiStreamPort`、只读 `StreamStartGate`、共享 `DjiOperationCoordinator`、范围为 1,000..60,000 毫秒的操作超时和可选诊断接收器。`StreamStartGate` 只能回答当前是否允许开始图传，不能暴露 DJI 类型或修改设备状态；它由组合根连接到 `device-connection` 的 `canStreamVideo` 能力。注入对象仍归调用方所有；门面创建并唯一拥有 `StreamStateStore`、`DjiStreamAdapter` 和 `StreamCommandHandler`。

`live-stream.start` 与 `live-stream.stop` 只有在对应 DJI 操作成功终态到达后才向 gateway 报告成功。接受提交、同步拒绝、失败、超时、取消、重复或延迟回调必须各自产生至多一个不泄漏 DJI 细节的安全结果。

## 3. 所有权与行为规则

只有 `stream-state-store` 持有图传事实，只有 `dji-stream-adapter` 可以调用 DJI 图传方法，所有 DJI 调用都经 `device-connection` 的 `dji-operation-coordinator`。命令处理器和校验器均不持有状态。MSDK `LiveStreamStatusListener` 是开始成功后的 DJI 图传运行态唯一来源：每个状态回调都会更新 `isStreaming` 或其指标；回调明确给出 `isStreaming=false` 时，必须立即将图传转入非活动失败态并经遥测发布，不能保留旧的“图传中”。

`live-stream.start` 必须在任何 DJI 调用前校验 RTMP URL 和 `StreamStartGate`。门禁拒绝时不得调用 DJI，且不得让图传状态进入启动中。只有 DJI 确认启动成功后才报告图传活动。停止、启动失败、超时、取消和设备断开必须产生稳定的非活动状态和安全提示；停止是恢复型操作，不使用启动门禁。重复或延迟 DJI 回调不得改变较新的状态，也不得完成同一中继命令两次。公开结果不得包含密码、令牌、文件路径、原始异常或 DJI 对象。

设备不可用时，组合根必须调用 `LiveStream.markDeviceUnavailable`。门面先将该通知委托给 `StreamStateStore.markDeviceUnavailable`，使运行中图传进入安全非活动状态，并让旧 DJI 回调因操作代际失效而无法恢复图传；随后必须再调用 `DjiStreamPort.stop`，停止仍在推送的 DJI RTMP。停止完成回调不得改写已失效状态。门面不得自行解释 RTMP URL、状态迁移或 DJI 错误。

## 4. 验证要求

每个二级模块必须有自己的 `CONTRACT.md`、纯规则及适配器边界的 JVM 测试。一级模块集成测试必须覆盖有效启动/停止、无效 URL 在 DJI 调用前拒绝、失败/超时/取消、重复回调、状态订阅、设备不可用和命令完成最多一次。全仓回归通过后本契约才可标记为已验证。

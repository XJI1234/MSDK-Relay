# stream-state-store 模块契约

状态：已实施并已验证；版本：1.0.0；所属一级模块：live-stream；Gradle 路径：:live-stream:stream-state-store

## 唯一职责

本模块是图传生命周期事实和指标的唯一所有者。它校验状态迁移、隔离旧操作回调，并暴露不可变快照和安全变更通知。它不校验 RTMP 语法、不调用 DJI、不发送 WebSocket 帧、不读取视频、不持久化密码/令牌，也不决定命令结果。

## 对外接口与规则

```text
StreamStateStore.create(diagnosticSink?) -> StreamStateStore
store.requestStart(validatedConfig) / requestStop() -> Accepted(operationId) | Rejected(reason)
store.markStarted/markStopped/markFailed/updateMetrics(operationId, ...) -> Applied | IgnoredStale
store.markDeviceUnavailable(notice) -> Applied(snapshot)
store.snapshot() -> StreamSnapshot
store.onChanged(listener) -> Registration
```

`StreamSnapshot` 含 revision、生命周期 `STOPPED|STARTING|STREAMING|STOPPING|FAILED`、是否已配置目标、安全提示和可选指标，绝不含 RTMP URL、查询、令牌或密码。`StreamOperationId` 是只用于拒绝延迟回调的不透明数字代际。指标限制为分辨率最多 64 码点、FPS 0..240、码率 0..1,000,000 kbps、RTT 0..60,000 ms，指标可缺失。

启动只可从 STOPPED/FAILED 进入 STARTING；停止只可从 STARTING/STREAMING 进入 STOPPING；匹配成功进入 STREAMING/STOPPED，匹配失败进入 FAILED。过期或重复回调返回 `IgnoredStale`，不变更且不通知。无效指标必须在状态改变前抛出 `IllegalArgumentException`，无效请求返回稳定枚举原因。

模块同步、线程安全，调用监听器时不得持有状态锁。监听器异常经诊断接缝记录，不能阻止其他监听器或回滚状态。注销必须等待在途回调结束并阻止返回后的排队回调，包括重入注册/注销。

测试必须覆盖初始状态、全部合法迁移、全部无效请求、过期/重复回调、指标边界、设备不可用重置、不可变快照、监听器顺序与失败、注销、重入回调、并发请求及写入时并发读取。

# stream-state-store 模块契约

状态：已实施并已验证；版本：1.1.0；所属一级模块：live-stream；Gradle 路径：:live-stream:stream-state-store

## 唯一职责

本模块是图传生命周期事实和指标的唯一所有者。它校验状态迁移、隔离旧操作回调，并暴露不可变快照和安全变更通知。它不校验 RTMP 语法、不调用 DJI、不发送 WebSocket 帧、不读取视频、不持久化密码/令牌，也不决定命令结果。

## 对外接口与规则

```text
StreamStateStore.create(diagnosticSink?) -> StreamStateStore
store.requestStart(validatedConfig) / requestStop() -> Accepted(operationId) | Rejected(reason)
store.markStarted/markStopped/markFailed/reportDjiStreaming/reportDjiStopped(operationId, ...) -> Applied | IgnoredStale
store.markDeviceUnavailable(notice) -> Applied(snapshot)
store.snapshot() -> StreamSnapshot
store.onChanged(listener) -> Registration
```

`StreamSnapshot` 含 revision、生命周期 `STOPPED|STARTING|STREAMING|STOPPING|FAILED`、是否已配置目标、安全提示、可选指标及可空 `djiStreaming`，绝不含 RTMP URL、查询、令牌或密码。`djiStreaming` 只能表示该代次最后收到的 `LiveStreamStatus.isStreaming`：`true` 为 MSDK 明确正在推流、`false` 为 MSDK 明确未推流、`null` 为尚未获得或已失效，不能由开始/停止 command callback 推断。生命周期 `STREAMING` 只表示 DJI 已接受当前开始操作并仍由该代次管理，不单独证明 MSDK 正在推流。`StreamOperationId` 是只用于拒绝延迟回调的不透明数字代际。指标限制为分辨率最多 64 码点、FPS 0..240、码率 0..1,000,000 kbps、RTT 0..60,000 ms，指标可缺失。

启动只可从 STOPPED/FAILED 进入 STARTING，并清空 `djiStreaming`；开始成功进入 STREAMING 但仍保持 `djiStreaming=null`，直到 `reportDjiStreaming` 收到 MSDK 的 `true`。`reportDjiStopped` 只接受当前 STREAMING 代次，记录 `djiStreaming=false` 并进入 FAILED；其他运行期失败、停止成功、设备不可用、图传源不可用和新代次开始均将 `djiStreaming` 置为 `null`，不得伪造 false。`markSourceUnavailable` 与 `markDeviceUnavailable` 语义分离：前者记录 `Video source unavailable`，保留“恢复后可人工开始”的含义；后者表示整个设备路径不可用。停止只可从 STARTING/STREAMING 进入 STOPPING；匹配成功进入 STOPPED。过期或重复回调返回 `IgnoredStale`，不变更且不通知。无效指标必须在状态改变前抛出 `IllegalArgumentException`，无效请求返回稳定枚举原因。

模块同步、线程安全，调用监听器时不得持有状态锁。监听器异常经诊断接缝记录，不能阻止其他监听器或回滚状态。注销必须等待在途回调结束并阻止返回后的排队回调，包括重入注册/注销。

测试必须覆盖初始状态、全部合法迁移、全部无效请求、过期/重复回调、指标边界、设备不可用重置、不可变快照、监听器顺序与失败、注销、重入回调、并发请求及写入时并发读取。

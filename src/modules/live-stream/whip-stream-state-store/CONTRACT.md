# whip-stream-state-store 二级模块契约

状态：实验模块契约。
Gradle 路径：`:live-stream:whip-stream-state-store`

## 唯一职责

`whip-stream-state-store` 只保存实验 WHIP 图传的设备隔离状态、操作代次和安全指标。它不调用 DJI、不建立网络、不解析 URL、不执行超时。

## 状态

每个设备状态为 `idle`、`connecting`、`publishing`、`stopping`、`failed` 或 `disconnected`。指标可以包含 codec、分辨率、帧率、码率和 RTT，但不得包含 URL、密码、令牌、原始异常或帧数据。

旧代次的指标、错误和终态不得覆盖新代次。设备断开必须使活动操作进入安全非活动状态。所有快照、列表和订阅值都必须是冻结副本。

## 对外接口

```kotlin
enum class WhipStreamLifecycle { IDLE, CONNECTING, PUBLISHING, STOPPING, FAILED, DISCONNECTED }
enum class WhipVideoCodec { H264 }
enum class WhipStreamFailure { NETWORK, SIGNALING, ICE, TIMEOUT, CANCELLED, UNSUPPORTED_CODEC, INTERNAL, DISCONNECTED }
enum class WhipStreamNotice { NONE, CONNECTING, PUBLISHING, STOPPING, FAILED, DISCONNECTED }

data class WhipStreamMetrics(
    val codec: WhipVideoCodec = WhipVideoCodec.H264,
    val resolution: String? = null,
    val fps: Double? = null,
    val bitrateKbps: Double? = null,
    val rttMillis: Long? = null,
)

data class WhipDeviceSnapshot(
    val deviceId: String,
    val revision: Long,
    val state: WhipStreamLifecycle,
    val targetConfigured: Boolean,
    val notice: WhipStreamNotice,
    val failure: WhipStreamFailure?,
    val metrics: WhipStreamMetrics?,
)

sealed interface WhipStartResult {
    data class Accepted(val operationId: Long) : WhipStartResult
    data class Rejected(val reason: WhipStartRejection) : WhipStartResult
}
enum class WhipStartRejection { INVALID_DEVICE_ID, ALREADY_ACTIVE }

sealed interface WhipStopResult {
    data class Accepted(val operationId: Long) : WhipStopResult
    data class Rejected(val reason: WhipStopRejection) : WhipStopResult
}
enum class WhipStopRejection { INVALID_DEVICE_ID, NO_ACTIVE_STREAM, ALREADY_STOPPING }

sealed interface WhipUpdateResult {
    data class Applied(val snapshot: WhipDeviceSnapshot) : WhipUpdateResult
    data class IgnoredStale(val operationId: Long) : WhipUpdateResult
}

data class WhipStreamStateEvent(
    val previous: WhipDeviceSnapshot,
    val current: WhipDeviceSnapshot,
)

fun interface WhipStreamStateListener { fun onChanged(event: WhipStreamStateEvent) }
fun interface WhipStreamStateRegistration { fun unregister() }
fun interface WhipStreamStateDiagnosticSink { fun record(kind: WhipStreamStateDiagnosticKind) }
enum class WhipStreamStateDiagnosticKind { LISTENER_FAILURE }

class WhipStreamStateStore {
    fun requestStart(deviceId: String, config: ValidatedWhipStreamConfig): WhipStartResult
    fun requestStop(deviceId: String): WhipStopResult
    fun markPublishing(deviceId: String, operationId: Long, metrics: WhipStreamMetrics? = null): WhipUpdateResult
    fun markStopped(deviceId: String, operationId: Long): WhipUpdateResult
    fun markFailed(deviceId: String, operationId: Long, failure: WhipStreamFailure): WhipUpdateResult
    fun markDisconnected(deviceId: String, operationId: Long): WhipUpdateResult
    fun markDeviceUnavailable(deviceId: String): WhipDeviceSnapshot
    fun updateMetrics(deviceId: String, operationId: Long, metrics: WhipStreamMetrics): WhipUpdateResult
    fun snapshot(deviceId: String): WhipDeviceSnapshot
    fun snapshots(): List<WhipDeviceSnapshot>
    fun onChanged(listener: WhipStreamStateListener): WhipStreamStateRegistration
}
```

`WhipStreamStateStore.create(diagnosticSink?)` 是唯一构造入口。`requestStart` 只消费 `ValidatedWhipStreamConfig` 的验证事实并立即丢弃地址，不保存或解析 URL。设备标识必须为非空、无控制字符且不超过 128 码点。

迁移规则固定为：`IDLE|FAILED|DISCONNECTED -> CONNECTING`；`CONNECTING|PUBLISHING -> STOPPING`；`CONNECTING -> PUBLISHING`；`STOPPING -> IDLE`；活动状态收到失败进入 `FAILED`，收到断开进入 `DISCONNECTED`。停止请求会生成新的操作代次，因此开始代次的迟到回调必然过期。`FAILED|DISCONNECTED|IDLE` 的快照不配置目标，成功发布和停止会清除对应失败/指标。

`markDeviceUnavailable(deviceId)` 会使该设备当前操作立即失效；活动状态进入 `DISCONNECTED`，目标、指标和失败细节被清除，`IDLE` 保持 `IDLE`。它返回最新快照并通知订阅者，不抛出平台异常。

指标中的分辨率最多 64 码点且不得含控制字符；FPS 为 0..240 的有限数，码率为 0..1,000,000 的有限数，RTT 为 0..60,000 毫秒。非法指标在状态改变前抛出 `IllegalArgumentException`。快照列表按设备标识升序返回且不可修改。

模块同步、线程安全，监听器调用不持有状态锁。监听器异常只记录 `LISTENER_FAILURE` 并继续通知其他监听器。注销等待在途回调完成，并阻止注销后的排队回调；监听器在自身回调中注销不得死锁。

## 验收

覆盖开始/停止迁移、重复终态、超时、取消、断开、迟到指标、旧代次、订阅隔离、排序、异常脱敏和多设备隔离。

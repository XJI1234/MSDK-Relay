# whip-publisher 二级模块契约

状态：实验模块契约。
Gradle 路径：`:live-stream:whip-publisher`

## 唯一职责

`whip-publisher` 定义把 H.264 编码帧发布到 WHIP 地址的端口。它不读取 DJI、不解释 Relay 命令、不保存公开状态，也不负责电脑端 WHEP 播放。

## 对外接口

```kotlin
interface WhipTransport {
    fun open(config: ValidatedWhipStreamConfig, listener: WhipTransportListener): WhipTransportOpenResult
    fun send(frame: EncodedVideoFrame): WhipTransportSendResult
    fun close(): WhipTransportCloseResult
}

interface WhipTransportListener {
    fun onConnected()
    fun onFailed(reason: WhipTransportFailure)
    fun onDisconnected()
}

sealed interface WhipTransportOpenResult {
    data object Accepted : WhipTransportOpenResult
    data class Rejected(val reason: WhipTransportRejection) : WhipTransportOpenResult
}

enum class WhipTransportRejection { ENCODED_H264_UNAVAILABLE, INVALID_CONFIGURATION, INTERNAL }
enum class WhipTransportFailure { SIGNALING, ICE, NETWORK, TIMEOUT, INTERNAL }

sealed interface WhipTransportSendResult {
    data object Accepted : WhipTransportSendResult
    data object Dropped : WhipTransportSendResult
    data object Backpressured : WhipTransportSendResult
    data object NotConnected : WhipTransportSendResult
    data class Failed(val reason: WhipTransportFailure) : WhipTransportSendResult
}

sealed interface WhipTransportCloseResult {
    data object Closed : WhipTransportCloseResult
    data object AlreadyClosed : WhipTransportCloseResult
    data class Failed(val reason: WhipTransportFailure) : WhipTransportCloseResult
}

interface WhipPublisherListener {
    fun onPublishing(generation: Long, metrics: WhipStreamMetrics?)
    fun onStopped(generation: Long)
    fun onFailed(generation: Long, reason: WhipPublisherFailure)
    fun onDisconnected(generation: Long)
}

data class WhipPublisherDependencies(
    val transport: WhipTransport,
    val diagnosticSink: WhipPublisherDiagnosticSink = WhipPublisherDiagnosticSink { },
)

sealed interface WhipPublisherStartResult {
    data class Accepted(val generation: Long) : WhipPublisherStartResult
    data object AlreadyActive : WhipPublisherStartResult
    data class Rejected(val reason: WhipPublisherStartRejection) : WhipPublisherStartResult
}

enum class WhipPublisherStartRejection {
    INVALID_CONFIGURATION,
    TRANSPORT_REJECTED,
    SOURCE_REJECTED,
    INTERNAL,
}

sealed interface WhipPublisherStopResult {
    data class Accepted(val generation: Long) : WhipPublisherStopResult
    data object AlreadyStopped : WhipPublisherStopResult
    data object AlreadyStopping : WhipPublisherStopResult
}

enum class WhipPublisherState { IDLE, CONNECTING, PUBLISHING, STOPPING, FAILED, DISCONNECTED }
enum class WhipPublisherFailure {
    ENCODED_H264_UNAVAILABLE,
    TRANSPORT_REJECTED,
    SOURCE_REJECTED,
    SIGNALING,
    ICE,
    NETWORK,
    TIMEOUT,
    INTERNAL,
    STOP_FAILED,
}

data class WhipPublisherSnapshot(
    val revision: Long,
    val generation: Long?,
    val state: WhipPublisherState,
    val parameterSetsReady: Boolean,
    val keyFrameReady: Boolean,
    val droppedFrames: Long,
    val failure: WhipPublisherFailure?,
)

fun interface WhipPublisherDiagnosticSink {
    fun record(kind: WhipPublisherDiagnosticKind)
}

enum class WhipPublisherDiagnosticKind { LISTENER_FAILURE }

class WhipPublisher {
    fun start(
        config: ValidatedWhipStreamConfig,
        source: EncodedVideoSource,
        listener: WhipPublisherListener,
    ): WhipPublisherStartResult
    fun stop(): WhipPublisherStopResult
    fun snapshot(): WhipPublisherSnapshot
}
```

`WhipPublisher.create(dependencies)` 是唯一构造入口。`start` 只接受经 `whip-stream-config` 验证的地址；实现仍必须在调用传输端口前重新确认该验证事实，不能接受空或未验证配置。发布器必须完成 WHIP HTTP offer/answer、ICE、DTLS、SRTP 和 H.264 RTP packetization，这些行为全部隐藏在 `WhipTransport` 适配器内。

发布器只能接受 H.264。`WhipTransport.send` 必须非阻塞；源回调线程上不得等待网络、ICE 或 HTTP。传输端口返回背压时优先丢弃非关键帧，且发布器只能统计丢帧，不能把背压异常传播到源线程。传输尚未连接时必须保留最近的 SPS、PPS 和关键帧，连接成功后立即发送；不得把连接前的启动帧静默丢弃。没有传输连接、关键帧或 SPS/PPS 时不能报告 `PUBLISHING`。SPS/PPS 既支持 Annex-B 也支持常见长度前缀 H.264 数据。

## 状态

状态为 `IDLE`、`CONNECTING`、`PUBLISHING`、`STOPPING`、`FAILED`、`DISCONNECTED`。每次开始创建新代次；停止、失败和连接关闭后，旧帧和旧回调不得改变新状态。每个代次最多通知一次 `onPublishing` 和一次终态回调。停止、失败时必须释放源监听和传输端口；资源释放异常只映射为固定失败事实。

监听器异常必须被吞掉并记录 `LISTENER_FAILURE`。快照不包含 URL、HTTP 响应、SDP、异常文本、凭据或帧数据。

## 验收

纯模块使用假帧源和假的信令/传输端口覆盖成功、拒绝、超时、ICE 失败、断网、背压、关键帧、重复完成、迟到帧和资源释放。真实 Android WebRTC 适配器另行做设备和 MediaMTX 集成验证。

# camera-stream-source 二级模块契约

状态：实验模块契约。
Gradle 路径：`:live-stream:camera-stream-source`

## 唯一职责

`camera-stream-source` 只定义从 DJI 相机流取得编码帧的端口。它不懂 WHIP、RTMP、SDP、ICE，不发送 Relay 命令，不管理图传业务状态。

## 对外接口

```kotlin
enum class CameraStreamCodec { H264, H265, UNKNOWN }

data class CameraStreamInfo(
    val codec: CameraStreamCodec,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val presentationTimeMs: Long,
    val isKeyFrame: Boolean,
)

fun interface CameraStreamListener {
    fun onReceiveStream(data: ByteArray, offset: Int, length: Int, info: CameraStreamInfo)
}

interface CameraStreamApi {
    fun addReceiveStreamListener(listener: CameraStreamListener)
    fun removeReceiveStreamListener(listener: CameraStreamListener)
}

fun interface CameraStreamSourceDiagnosticSink {
    fun record(kind: CameraStreamSourceDiagnosticKind)
}

enum class CameraStreamSourceDiagnosticKind {
    UNSUPPORTED_CODEC,
    INVALID_FRAME,
    LISTENER_FAILURE,
    PLATFORM_FAILURE,
}

object CameraStreamSource {
    fun create(
        api: CameraStreamApi,
        diagnosticSink: CameraStreamSourceDiagnosticSink = CameraStreamSourceDiagnosticSink { },
    ): EncodedVideoSource
}
```

`camera-stream-source` 只实现上述平台无关端口。Android 适配器必须使用 `ICameraStreamManager.addReceiveStreamListener` 获取 `byte[]`、偏移、长度和 `StreamInfo`，再映射为 `CameraStreamInfo`；纯模块本身不引用 DJI 类。`CameraStreamInfo.codec` 为 H.264 时才产生 `EncodedVideoFrame`；H.265/未知编码丢弃、记录 `UNSUPPORTED_CODEC`，并通过 `EncodedVideoSource.start(..., onFailure)` 上报 `SourceFailure.UNSUPPORTED_CODEC`，每个会话只报一次。必须保留关键帧标记、PTS、分辨率和帧率。

源必须在停止、失败和新代次开始时移除精确 listener 实例。旧 listener 的回调必须被丢弃。`EncodedVideoListener` 抛出的异常、非法帧和平台移除异常不得穿透 SDK 回调线程，只能记录固定诊断事实。源不创建手机显示 Surface，也不启动 DJI `LiveStreamManager`。

## 验收

JVM 使用假的 DJI API 覆盖注册、回调、H.264/H.265、偏移长度、关键帧、停止释放、同步异常和迟到回调；Android Debug 构建验证真实 MSDK 5.17 类型可编译。

# whip-live-stream 一级模块契约

状态：实验模块契约，是本模块唯一权威。
Gradle 路径：`:live-stream:whip-live-stream`

## 唯一职责

`whip-live-stream` 是手机端实验图传组合根。它把一个设备的 WHIP 命令、H.264 CameraStream 源、WHIP 发布器和设备隔离状态组合起来，向 Relay 暴露独立的 `live-stream-webrtc.start` / `live-stream-webrtc.stop` 命令。

它不调用 DJI、不创建 WebRTC/HTTP、不解析 WHIP URL、不实现 CameraStream、不复刻旧 RTMP 状态，也不修改 `LiveStream` 的旧命令和旧适配器。

## 对外接口

```kotlin
data class WhipLiveStreamDependencies(
    val deviceId: String,
    val source: EncodedVideoSource,
    val transport: WhipTransport,
    val diagnosticSink: WhipStreamStateDiagnosticSink = ...,
)

class WhipLiveStream {
    companion object { fun create(dependencies): WhipLiveStream }
    fun commandHandler(): CommandHandler
    fun snapshot(): WhipDeviceSnapshot
    fun onChanged(listener): WhipStreamStateRegistration
    fun markDeviceUnavailable(): WhipDeviceSnapshot
    fun close()
}
```

构造时 `deviceId` 必须为非空、无控制字符且不超过 128 个码点；依赖不得为 null。门面唯一拥有 `WhipStreamStateStore`、`WhipPublisher` 和 `WhipCommandHandler`，调用方只拥有注入的 source 和 transport。

## 命令和状态

`commandHandler()` 只接受 `whip-command-handler` 定义的：

```text
live-stream-webrtc.start { whipUrl: string }
live-stream-webrtc.stop  {}
```

启动命令先创建设备操作代次，再启动 `WhipPublisher`；停止命令先创建新的停止代次，再停止发布器。当状态已经是 `FAILED`、`DISCONNECTED` 或 `IDLE` 时，stop 必须成功完成（幂等），不得把「已经没有活动流」映射为命令失败。动作返回 `Accepted` 只表示操作已提交；手机发布器进入 `PUBLISHING` 后状态才变为 `PUBLISHING`，不能把命令接受解释为电脑首帧显示。

发布器回调只能更新匹配的设备操作代次：发布成功映射为 `PUBLISHING`，停止成功映射为 `IDLE`，连接断开映射为 `DISCONNECTED`，信令/ICE/网络/超时/源失败映射为稳定的 `WhipStreamFailure`。旧发布代次、重复终态和迟到帧不得改变新操作，也不得重复完成 Relay 命令。

## 设备不可用和关闭

`markDeviceUnavailable()` 必须停止当前发布器、使状态进入安全非活动状态、使所有旧回调失效；不会自动重连。`close()` 幂等，必须停止源和传输，监听器异常不得阻止资源清理。

## 验收

契约测试先写后实现，覆盖精确命令注册、接受与终态时机、状态和发布器代次、启动/停止失败、断开、设备不可用、旧回调、多设备实例隔离、监听器异常和幂等关闭。测试不得依赖 DJI、Android、WebRTC 或网络。

# android-whip-publisher-adapter 二级模块契约

状态：已封存的 WebRTC/WHIP 旁路源码与独立测试；不纳入生产组合根。

> 封存规则：本模块只保留给历史旁路的源码和测试，生产 RTMP `LiveStream`、`MobileRelayGraph`、命令注册和 APK 依赖均不得调用或装配它。重新启用必须先取得业务批准，并同步更新两端根契约、生产装配和跨端验证。
Gradle 路径：`:live-stream:android-whip-publisher-adapter`

## 唯一职责

`android-whip-publisher-adapter` 是 `whip-publisher` 的 Android WebRTC/HTTP 传输适配器。它把已编码的 H.264 帧通过一个 WHIP 发布会话送到 MediaMTX，并把 WebRTC/HTTP 的平台事实转换为 `whip-publisher` 已定义的 `WhipTransport` 结果和回调。

它不调用 DJI、不解析 Relay 命令、不保存设备业务状态、不提供 WHEP 播放，也不把 `PeerConnection`、SDP、HTTP 响应、凭据或异常文本泄漏给上层。

## 对外接口

```kotlin
class AndroidWhipTransport {
    companion object {
        fun create(context: Context, options: AndroidWhipTransportOptions = ...): WhipTransport
    }
}

data class AndroidWhipTransportOptions(
    val queueCapacity: Int = 3,
    val signalingTimeoutMs: Long = 15_000,
)
```

返回值必须实现 `:live-stream:whip-publisher` 的 `WhipTransport`：

```kotlin
open(config, listener) -> WhipTransportOpenResult
send(frame) -> WhipTransportSendResult
close() -> WhipTransportCloseResult
```

`create` 只接受 Android `Context` 和受限选项。`queueCapacity` 必须为 1..8，`signalingTimeoutMs` 必须为 1,000..15,000；非法选项在构造时抛出 `IllegalArgumentException`。适配器的公开接口不包含任何 `org.webrtc` 类型。

## 建立发布会话

`open` 必须再次通过 `WhipStreamConfigValidator` 验证传入的 `ValidatedWhipStreamConfig.whipUrl`。验证失败返回 `Rejected(INVALID_CONFIGURATION)`，不能发出网络请求。已有会话再次 `open` 返回 `Rejected(INTERNAL)`。

`open` 在调用线程只创建代次并提交异步任务，不能等待 HTTP、ICE、DTLS 或网络；异步任务按以下顺序执行：

1. 创建本代次独立的 WebRTC PeerConnection、只发送的视频 transceiver 和自定义 H.264 编码器。
2. 创建 offer，设置本地描述，并等待 `IceGatheringState.COMPLETE`。发送给 WHIP 的 SDP 必须已经包含本地 ICE candidates；本适配器不依赖 trickle ICE/PATCH 才能工作。
3. 使用 `POST whipUrl`、`Content-Type: application/sdp` 发送 offer。只接受 2xx 且包含非空 SDP answer 的响应，然后设置远端描述。
4. 只有 PeerConnection 进入 `CONNECTED` 或 `COMPLETED` 后才调用一次 `WhipTransportListener.onConnected()`。`Accepted` 只表示异步建立任务已接受，不表示已经连接。

HTTP 连接和读取必须有超时，不能把网络阻塞放到 CameraStream 回调线程。非 2xx、空 answer、SDP 设置失败、ICE 失败、网络异常和超时分别稳定映射为 `SIGNALING`、`ICE`、`NETWORK` 或 `TIMEOUT`；公开结果不带状态码、响应体或异常文本。

## 编码帧注入

当前 `stream-webrtc-android:1.1.1` 通过 `PeerConnectionFactory.Builder.setVideoEncoderFactory` 和自定义 `VideoEncoder` 接收 WebRTC 的占位 `VideoFrame`，再把 DJI 已编码 H.264 包装为 `EncodedImage`。占位帧不得承载 DJI 的 YUV 数据，适配器不得调用解码器、软件/硬件视频编码器或 YUV 转码路径。

如果运行时平台端口无法确认这条无转码注入路径可用，`open` 必须在创建 PeerConnection 前返回 `Rejected(ENCODED_H264_UNAVAILABLE)`，不能伪装成连接成功或偷偷退化为 YUV。

`send` 只能在本代次已经 `onConnected` 后接受帧。它必须：

- 在 CameraStream 回调线程上只做有限队列检查和必要的编码数据复制，不执行 HTTP、SDP、ICE、DTLS、SRTP 或阻塞 WebRTC 操作；
- 复制 `offset..offset+length` 的 H.264 字节，使异步消费不依赖 DJI 回调数组的生命周期；
- Annex-B 直接发送，常见 4 字节/长度前缀 AVCC 转换为 Annex-B；转换只改封装，不解码、不编码；
- 队列已满时优先丢弃已排队的非关键帧以接纳新的关键帧；若队列全是关键帧或新帧不是关键帧则返回 `Backpressured`，不阻塞、不无限增长；队列接受后返回 `Accepted`，平台发送在适配器工作线程完成；
- 非法 H.264 封装返回 `Dropped`；未连接返回 `NotConnected`；平台异步失败通过 `onFailed` 通知。

适配器不能改变 H.264 的关键帧、SPS/PPS 内容和时间戳事实。发布器仍负责判定 SPS/PPS、关键帧和 `PUBLISHING`，本适配器不得把“已排队”解释成“已发布”。

## 代次、停止和资源

每次 `open` 创建独立代次。停止由 `close` 完成；`close` 必须取消超时、清空帧队列、释放 PeerConnection、视频源、视频轨道、RTP sender、工作线程和 HTTP 会话。重复 `close` 返回 `AlreadyClosed`。

失败、断开和关闭都必须最多产生一次对应终态事实。旧代次的 offer、HTTP answer、ICE、连接状态、编码回调和排队帧必须被忽略，不能连接、发送或失败新代次。平台释放异常只能映射为 `WhipTransportCloseResult.Failed(INTERNAL)`，不能穿透 DJI 或 WebRTC 回调线程。

适配器不自动重连；上层收到失败或断开后决定是否创建新代次。监听器异常必须被吞掉，不得破坏资源清理。

## 验收

契约测试必须先覆盖并观察失败，再实现：非法配置和 H.264 能力门禁、异步 offer/answer 顺序、完整 ICE SDP、HTTP/SDP/ICE/超时错误映射、连接后才能发送、有限队列背压、CameraStream 数组复制、Annex-B/AVCC 封装、停止释放、重复终态、旧代次回调和监听器异常。

Android Debug 编译必须通过真实 `stream-webrtc-android:1.1.1` 类型。真实设备或仿真集成必须验证 MediaMTX WHIP 接受 offer、首个关键帧可发布、持续 30fps 发送不阻塞 DJI 回调，并记录从 CameraStream 时间戳到电脑 WHEP 首帧的实测延迟。

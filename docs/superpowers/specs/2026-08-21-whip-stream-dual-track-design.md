# WHIP 低延迟图传双轨实验设计

状态：契约阶段，尚未进入实现。

## 目标

保留当前 `LiveStream` 的 RTMP 生产链路，同时增加一个独立的 `WhipStream` 实验链路。默认仍接收 `live-stream.start { rtmpUrl }`；实验链路使用独立的 `live-stream-webrtc.start { whipUrl }`，两者互不改变协议字段和状态。

## 手机数据流

```text
ICameraStreamManager.ReceiveStreamListener
  -> encoded-video
  -> whip-publisher
  -> MediaMTX WHIP
```

只使用 DJI 的编码流回调，不使用输出 YUV 的 `CameraFrameListener`。实验发布器只接受 H.264。H.265、缺少关键帧参数、不可用 PTS 和不支持的帧格式必须稳定失败，禁止静默转码。

## 新模块边界

- `live-stream/encoded-video`：平台无关的编码帧和帧源接口。
- `live-stream/camera-stream-source`：平台无关的相机编码帧源端口。
- `live-stream/whip-publisher`：平台无关的 WHIP 发布端口和发布状态。
- `live-stream/whip-stream-config`：独立的 WHIP URL 校验器。
- `live-stream/whip-command-handler`：解释实验命令的精确字段。
- `live-stream/whip-stream-state-store`：按设备保存实验发布状态和安全指标。
- `live-stream/android-camera-stream-adapter`：唯一调用 DJI `ICameraStreamManager` 的实现。
- `live-stream/android-whip-publisher-adapter`：唯一依赖 Android WebRTC/HTTP 的发布实现。

现有 `live-stream`、`stream-config-validator`、`stream-command-handler`、`dji-stream-adapter`、`stream-state-store` 和 `MsdkV5LiveStreamApi` 先不改。

## 编码帧契约

编码帧至少包含 codec、data slice、width、height、frameRate、presentationTimeMs 和 isKeyFrame。字节所有权必须明确：发布器不得在回调返回后继续读取未拥有的数组。源回调不得阻塞；发布器拥塞时只允许丢弃非关键帧。停止或代次失效后，迟到帧必须被忽略。

## 发布状态

新发布状态为 `idle`、`connecting`、`publishing`、`stopping`、`failed`、`disconnected`。`publishing` 只表示 WHIP 会话成功发布，不表示桌面已经显示首帧。每个设备独立串行，同一操作最多完成一次，迟到回调不得污染下一代。

## 验收

纯 Kotlin 模块先完成 JVM 契约测试；Android DJI 适配器必须完成 Debug 编译；WebRTC Publisher 先使用假 H.264 帧对接假 WHIP/MediaMTX，再接入真实 DJI 回调。真机必须单独验证 H.264/H.265、关键帧间隔、PTS、停止释放、断网恢复、温度和持续运行。

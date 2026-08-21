# whip-publisher 二级模块契约

状态：实验设计，尚未实现。

## 唯一职责

`whip-publisher` 定义把 H.264 编码帧发布到 WHIP 地址的端口。它不读取 DJI、不解释 Relay 命令、不保存公开状态，也不负责电脑端 WHEP 播放。

## 对外接口

```text
WhipPublisher.create(dependencies) -> WhipPublisherInstance
instance.start(whipUrl, source, listener) -> StartResult
instance.stop(listener) -> StopResult
instance.snapshot() -> PublisherSnapshot
```

发布器必须完成 WHIP HTTP offer/answer、ICE、DTLS、SRTP 和 H.264 RTP packetization。WHIP URL 只允许 HTTP/HTTPS、无凭据、无查询串和 fragment，并且 path 必须以 `/whip` 结尾。

发布器只能接受 H.264。网络发送不得阻塞 DJI 源回调；拥塞时优先丢弃非关键帧。没有可用关键帧或 SPS/PPS 时不能报告 `publishing`。

## 状态

状态为 `idle`、`connecting`、`publishing`、`stopping`、`failed`、`disconnected`。每次开始创建新代次；停止、失败和连接关闭后，旧帧和旧回调不得改变新状态。每个操作最多通知一次终态。

## 验收

纯模块使用假帧源和假的信令/传输端口覆盖成功、拒绝、超时、ICE 失败、断网、背压、关键帧、重复完成、迟到帧和资源释放。真实 Android WebRTC 适配器另行做设备和 MediaMTX 集成验证。

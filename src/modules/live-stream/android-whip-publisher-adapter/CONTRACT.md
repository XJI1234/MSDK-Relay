# android-whip-publisher-adapter 二级模块契约

状态：实验设计，尚未实现。

## 唯一职责

`android-whip-publisher-adapter` 是 `whip-publisher` 的 Android WebRTC/HTTP 实现。它负责把已编码 H.264 帧发送到 WHIP，并把平台连接状态转换为平台无关的发布状态。

它不调用 DJI、不解析 Relay 命令、不保存图传业务状态、不提供 WHEP 播放，也不把 PeerConnection 对象泄漏给上层。

## 资源规则

每次 start 建立独立 PeerConnection 代次。停止、失败、超时和关闭都必须释放 PeerConnection、线程、HTTP 会话和帧监听。旧代次的 ICE、信令、发送回调和帧不得影响新代次。

实现必须明确编码帧注入方式和背压策略；如果当前 Android WebRTC 依赖不能无转码注入 H.264，必须在启动前返回稳定的 `ENCODED_H264_UNAVAILABLE`，不能悄悄退化为 YUV 转码。

## 验收

先用假 H.264 帧对接假 WHIP/MediaMTX 完成集成测试，再接 DJI source。测试覆盖成功、SDP 失败、ICE 失败、HTTP 失败、断网、停止、重复终态、迟到回调、背压和资源释放。

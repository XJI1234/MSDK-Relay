# camera-stream-source 二级模块契约

状态：实验设计，尚未实现。

## 唯一职责

`camera-stream-source` 只定义从 DJI 相机流取得编码帧的端口。它不懂 WHIP、RTMP、SDP、ICE，不发送 Relay 命令，不管理图传业务状态。

## 对外接口

```text
CameraStreamSource.create(api) -> EncodedVideoSource
```

实现必须使用 `ICameraStreamManager.addReceiveStreamListener` 获取 `byte[]`、偏移、长度和 `StreamInfo`。`StreamInfo.mimeType` 为 H.264 时才产生帧；H.265 通过稳定的 `UNSUPPORTED_CODEC` 事实报告。必须保留关键帧标记、PTS、分辨率和帧率。

源必须在停止、失败和新代次开始时移除监听器。旧监听器的回调必须被丢弃。源不应创建手机显示 Surface，也不能同时启动 DJI `LiveStreamManager`。

## 验收

JVM 使用假的 DJI API 覆盖注册、回调、H.264/H.265、偏移长度、关键帧、停止释放、同步异常和迟到回调；Android Debug 构建验证真实 MSDK 5.17 类型可编译。

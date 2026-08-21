# android-camera-stream-adapter 二级模块契约

状态：实验设计，尚未实现。

## 唯一职责

`android-camera-stream-adapter` 是 `camera-stream-source` 的 DJI MSDK v5.17 Android 实现。它只取得飞机相机的 H.264 编码帧并转换为 `EncodedVideoFrame`，不实现 WHIP、不调用 Relay、不渲染 Surface。

## 实现规则

- 使用 `MediaDataCenter.getInstance().cameraStreamManager`。
- 使用 `addReceiveStreamListener`，不使用 YUV `addFrameListener` 作为发布路径。
- 按 `StreamInfo` 转换 mime type、偏移、长度、宽高、帧率、PTS 和关键帧。
- H.264 之外的编码报告稳定错误，不进行隐式转码。
- 停止时移除精确的 listener 实例；失败、停止或新代次后的迟到回调全部忽略。
- 不与当前 `MsdkV5LiveStreamApi` 同时占用 DJI 图传管理器。

## 验收

JVM 测试覆盖假的 MSDK 回调、H.264、H.265、非法范围、关键帧、PTS、同步异常、监听器释放和代次隔离。Android Debug 编译必须通过真实 MSDK 5.17 API。真机必须验证目标飞机型号的编码格式、帧率、关键帧间隔和持续运行稳定性。

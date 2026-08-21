# encoded-video 二级模块契约

状态：实验设计，尚未实现。

## 唯一职责

`encoded-video` 定义手机内部传递编码视频帧的最小平台无关接口。它不调用 DJI、不建立网络、不创建线程、不实现编解码。

## 对外模型

```text
EncodedVideoFrame {
  codec: H264,
  data: ByteArray,
  offset: Int,
  length: Int,
  width: Int,
  height: Int,
  frameRate: Int,
  presentationTimeMs: Long,
  isKeyFrame: Boolean
}

EncodedVideoSource {
  start(listener) -> SourceStartResult
  stop() -> SourceStopResult
}
```

`offset` 和 `length` 必须在数组范围内，宽高和时间戳必须有效。源只能同步使用回调帧，除非明确复制数据；调用方不得修改已交给发布器的数组。

## 生命周期

开始、停止最多各完成一次。停止后迟到帧被忽略。监听器异常不能反向杀死源线程，也不能把原始异常传播到 Relay。

## 验收

覆盖帧边界、关键帧、时间戳、重复停止、迟到帧、监听器异常、数组所有权和多实例隔离。

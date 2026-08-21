# encoded-video 二级模块契约

状态：实验模块契约。

## 唯一职责

`encoded-video` 定义手机内部传递编码视频帧的最小平台无关接口。它不调用 DJI、不建立网络、不创建线程、不实现编解码。

## 对外模型

```text
EncodedVideoFrame(
  data: ByteArray,
  offset: Int,
  length: Int,
  width: Int,
  height: Int,
  frameRate: Int,
  presentationTimeMs: Long,
  isKeyFrame: Boolean,
) {
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

interface EncodedVideoSource {
  start(listener: EncodedVideoListener) -> SourceStartResult
  stop() -> SourceStopResult
}

`EncodedVideoFrame` 构造时必须拒绝空数据、负偏移/长度、越过数组边界、非正宽高、非正帧率和负 PTS。`data`、偏移、长度和元数据在实例创建后不可变；模块不复制数组，调用方必须保证在同步消费完成前不修改数组。

`SourceStartResult` 只有 `Started`、`AlreadyStarted` 和 `Failed(reason)`；`SourceStopResult` 只有 `Stopped`、`AlreadyStopped` 和 `Failed(reason)`。源回调同步执行，监听器抛出的异常必须被源吞掉并转为固定诊断事实，不能从源线程向上传播。
```

`offset` 和 `length` 必须在数组范围内，宽高和时间戳必须有效。源只能同步使用回调帧，除非明确复制数据；调用方不得修改已交给发布器的数组。

## 生命周期

开始、停止最多各完成一次。停止后迟到帧被忽略。监听器异常不能反向杀死源线程，也不能把原始异常传播到 Relay。

## 验收

覆盖帧边界、关键帧、时间戳、重复停止、迟到帧、监听器异常、数组所有权和多实例隔离。

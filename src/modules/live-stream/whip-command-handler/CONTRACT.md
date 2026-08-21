# whip-command-handler 二级模块契约

状态：实验设计，尚未实现。

## 唯一职责

`whip-command-handler` 只解释 `live-stream-webrtc.start` 和 `live-stream-webrtc.stop`，校验字段，并把已接受动作交给注入的 WHIP 图传动作。它不调用 DJI、不建立网络、不保存状态。

## 命令字段

启动只接受一个字符串字段 `whipUrl`：

```text
live-stream-webrtc.start { whipUrl: string }
live-stream-webrtc.stop  {}
```

字段必须精确匹配，不能同时携带 `rtmpUrl`、`protocol` 或未知字段。启动在配置校验完成前不得调用动作。接受只表示提交，不得立即生成成功结果；终态必须通过注入的 completion 报告。

## 验收

覆盖精确字段、全部 URL 拒绝原因、动作委托、同步拒绝、接受与终态的时机、重复终态、未知命令、并发独立读取和异常脱敏。

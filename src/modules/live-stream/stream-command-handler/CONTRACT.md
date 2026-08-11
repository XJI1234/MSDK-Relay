# stream-command-handler 模块契约

状态：已实施并已验证；版本：1.0.0；所属一级模块：live-stream；Gradle 路径：:live-stream:stream-command-handler

## 唯一职责与接口

本模块解释 `live-stream.start` 和 `live-stream.stop`，通过 `stream-config-validator` 校验命令字段，并将已接受操作委托给注入的图传动作。它不调用 DJI、不建立 RTMP Socket、不持有图传状态、不发布遥测、不解析 WebSocket 帧，也不暴露解析/DJI 异常。

```text
handler.handle(command) -> Accepted | Succeeded | Rejected(reason)
handler.handle(command, completion) -> Accepted | Succeeded | Rejected(reason)
```

启动只接受一个字符串字段 `rtmpUrl`，停止不接受字段。调用 `StreamCommandActions.start` 前必须完成配置校验。接受只表示已提交；其 `StreamActionCompletion` 向父门面报告 `SUCCEEDED`、`FAILED`、`TIMED_OUT` 或 `CANCELLED`。提交时不得生成成功结果。

未知命令、错误/缺失字段、无效 RTMP 配置、能力前置条件失败和畸形动作结果必须映射为稳定枚举原因。拒绝不得包含原 URL、密码/令牌、异常或 DJI 值。处理器无状态、线程安全；操作串行化属于 `dji-stream-adapter` 和共享协调器。

测试必须覆盖两个命令、精确字段/类型校验、全部校验失败类别、动作委托与拒绝、接受和终态的时机区别、组合边界的重复终态回调、未知命令及并发独立读取。

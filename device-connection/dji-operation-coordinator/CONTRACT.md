# dji-operation-coordinator 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`device-connection`
Gradle 路径：`:device-connection:dji-operation-coordinator`

## 1. 唯一职责

本模块串行执行 DJI 操作，统一处理排队、超时、取消、执行器异常和重复完成回调。它不解释配对、航线或直播业务含义。

## 2. 对外接口

```text
coordinator.submit(action, timeoutMillis, completion)
  -> Accepted(cancellation) | Rejected

cancellation.cancel() -> Cancelled | AlreadyFinished
```

`action` 只能通过传入的 completion 报告 `success` 或 `failure`。每个已接受操作最终恰好得到一次 `SUCCEEDED`、`FAILED`、`TIMED_OUT` 或 `CANCELLED`；结果不携带 DJI 原始异常。

## 3. 规则

- 允许多个操作排队，但同一时刻只启动一个操作。
- 超时范围为 1,000 到 60,000 ms；范围外直接拒绝，不入队。
- 超时从 action 真正开始执行时计算，不包含等待队列时间。
- 取消排队操作后不启动 action；取消运行中操作后立即完成为 `CANCELLED`，后续 DJI 回调被丢弃。
- action 抛异常、执行器拒绝和调度器失败都完成为 `FAILED` 并继续下一项。
- 停止或断开时，调用方通过各自 cancellation 取消；本模块不决定何时停止设备。

## 4. 测试要求

纯 JVM 测试覆盖串行、成功、失败、异常、超时、排队取消、运行中取消、重复完成、执行器拒绝和计时器取消。

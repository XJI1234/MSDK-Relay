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

`TIMED_OUT` 或运行中 `CANCELLED` 只表示本程序未能继续等待该操作，**不表示 DJI 已停止执行**。这种情况下协调器进入“硬件结果未确认”隔离：会立即把终态交给原调用方，但仍占用唯一 DJI 操作槽位，取消尚未开始的排队项并拒绝新的提交。只有同一 action 之后的 `success` / `failure` 回调，或该 action 调用一次 `confirmHardwareSettled()`，才能解除隔离。

`confirmHardwareSettled()` 只能由 action 持有的、与该写操作一一对应的 DJI 官方状态观察调用；它只在该 action 已超时或取消、仍占用槽位时生效。它不能由 WebSocket 回包、页面状态、缓存值或后续命令调用，也不能提前释放仍在等待回执的操作。状态监听和 RTMP 媒体数据不属于本模块的 DJI 写操作，不能被此队列阻塞。

若 DJI 的终态回调晚于 `TIMED_OUT` 或 `CANCELLED`，协调器在释放槽位后只调用该 action 的 `onLateDjiCompletion(outcome)`。该钩子只能安排必要的后续恢复操作，不能向原调用方再次报告结果。它在下一项排队操作开始前运行，因此恢复操作可按 FIFO 规则重新进入同一队列。

## 3. 规则

- 允许多个操作排队，但同一时刻只启动一个操作。
- 超时范围为 1,000 到 60,000 ms；范围外直接拒绝，不入队。
- 超时从 action 真正开始执行时计算，不包含等待队列时间。
- 取消排队操作后不启动 action；取消运行中操作后立即完成为 `CANCELLED` 并进入硬件结果未确认隔离，后续 DJI 回调只用于解除隔离，不得重复通知调用方。
- 超时后立即完成为 `TIMED_OUT` 并进入硬件结果未确认隔离；不得在该回调前启动下一项 DJI 操作。
- action 在调用 DJI 前无法调度或初始化时，完成为 `FAILED` 并继续下一项；action 已开始后抛异常也进入硬件结果未确认隔离，因为无法证明 DJI 没有收到调用。

## 4. 测试要求

纯 JVM 测试覆盖串行、成功、失败、异常、超时隔离、排队取消、运行中取消隔离、迟到回调恢复、权威状态确认恢复、重复完成、执行器拒绝和计时器取消。

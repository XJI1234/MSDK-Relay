# runtime-diagnostics.gateway-diagnostic-publisher 二级模块契约

状态：设计并实现中
版本：0.1.0
父模块：[`../CONTRACT.md`](../CONTRACT.md)
Gradle 路径：`:runtime-diagnostics:gateway-diagnostic-publisher`

## 1. 唯一职责

本模块把 `diagnostic-core` 的最早未确认事件组成批次，并通过 `relay-gateway` 已存在的发布接口发送；它只处理发送时机和确认回写。

## 2. 接口与规则

```text
GatewayDiagnosticPublisher.start() / stop()
GatewayDiagnosticPublisher.flush() -> FlushResult
GatewayDiagnosticPublisher.onAcknowledged(runId, acknowledgedSequence)
```

- `start` 监听 gateway 状态；每次进入 `ACTIVE` 都调用一次 `flush`。
- `flush` 在非 `ACTIVE` 状态返回拒绝且不删除事件；在 `ACTIVE` 时按队列顺序发送至多 32 条。
- gateway 返回“已交给传输层”不等于电脑已保存，只有 `diagnostic-ack` 能确认删除。
- 一次 `flush` 同时至多有一个未确认批次；收到确认、出站写入拒绝或会话代次变化后才允许重新发送。
- `stop` 注销监听；不删除队列，不关闭 gateway，不等待网络操作。

本模块不得引用 OkHttp、Android Context、DJI、文件路径或业务模块类型。

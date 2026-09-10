# runtime-diagnostics.gateway-diagnostic-publisher 二级模块契约

状态：设计并实现中
版本：0.1.0
父模块：[`../CONTRACT.md`](../CONTRACT.md)
Gradle 路径：`:runtime-diagnostics:gateway-diagnostic-publisher`

## 1. 唯一职责

本模块把 `diagnostic-core` 的未确认事件组成批次发给电脑：发送与删除分开。未确认的事件留在手机上，确认后才删。

## 2. 接口与规则

```text
GatewayDiagnosticPublisher.start() / stop()
GatewayDiagnosticPublisher.flush() -> FlushResult
```

- `start` 监听 gateway 状态、journal 新记录和 `diagnostic-ack`。每次进入 `ACTIVE`、每次新记录，都尝试 `flush`。
- `flush` 在非 `ACTIVE` 状态不发送、不删除。`ACTIVE` 时按序号发送尚未发出的批次，窗口最多 4 批（每批至多 32 条）；不必等上一批 ack 才能发下一批。
- 未确认批次超时后只重发最老的一批，不把新事件当作已删除。
- gateway 返回“已交给传输层”不等于电脑已保存，只有 `diagnostic-ack` 能确认删除。
- 会话离开 `ACTIVE` 时重置发送游标，队列仍保留；再次 `ACTIVE` 从最老未确认事件重发。
- `stop` 注销监听；不删除队列，不关闭 gateway，不等待网络操作。

本模块不得引用 OkHttp、Android Context、DJI、文件路径或业务模块类型。

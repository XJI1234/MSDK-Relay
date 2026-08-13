# runtime-diagnostics.diagnostic-core 二级模块契约

状态：设计并实现中
版本：0.1.0
父模块：[`../CONTRACT.md`](../CONTRACT.md)
Gradle 路径：`:runtime-diagnostics:diagnostic-core`

## 1. 唯一职责

本模块把调用方提供的诊断意图转换为可安全记录、可排序、可确认删除的不可变事件队列。它不接触 Android、文件、网络和 DJI。

## 2. 接口

```text
DiagnosticJournal.record(level, module, eventCode, operationId?, detail) -> DiagnosticEvent
DiagnosticJournal.pending(maxEvents) -> List<DiagnosticEvent>
DiagnosticJournal.acknowledge(runId, acknowledgedSequence) -> AcknowledgementResult
DiagnosticJournal.snapshot() -> DiagnosticJournalSnapshot
```

- 构造时调用方提供固定 `runId`、容量、时钟和可选的持久化端口；容量必须大于 0。
- `record` 永不向业务调用方抛出持久化端口或监听器异常；调用方获得的事件已经过脱敏和长度限制。
- `pending` 总是按 `sequence` 升序返回最早的未确认事件，且不改变队列。
- `acknowledge` 仅接受当前 `runId` 且不小于已确认序号的确认；未知运行批次和旧确认不删除任何事件。
- 所有公开集合和事件都是不可变快照；并发调用不得重复分配序号或破坏排序。

## 3. 固定限制

| 字段 | 限制 |
| --- | --- |
| `runId`、`operationId` | 1..128 Unicode code point，非空白、无控制字符 |
| `module`、`eventCode` | 1..64 ASCII 字符，首字符为字母，后续仅字母、数字、`-`、`_`、`.` |
| `safeDetail` | 0..512 Unicode code point，无控制字符 |
| 单次发送批次 | 1..32 条事件 |

脱敏至少替换下列模式为 `[REDACTED]`：URL 中的 user-info 与 query/fragment、`key`/`token`/`secret`/`authorization` 等键值、Windows/Unix 绝对路径。脱敏后仍超长的文本必须截断，不得抛异常。

## 4. 状态与错误

事件从 `PENDING` 进入 `ACKNOWLEDGED` 后删除。容量淘汰仅发生在最旧的 `PENDING` 事件；淘汰计数必须反映在下一条可记录事件的安全详情中。持久化失败仅增加统计，不得使 `record` 失败。

模块不产生 `runId`，不决定日志等级，不打开文件，不发送帧，也不根据业务异常自动重试。

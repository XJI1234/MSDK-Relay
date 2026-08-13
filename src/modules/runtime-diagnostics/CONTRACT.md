# runtime-diagnostics 一级模块契约

状态：设计并实现中
版本：0.1.0
所属程序：MSDK Relay Android
模块标识：`runtime-diagnostics`

## 1. 唯一职责

`runtime-diagnostics` 只负责把手机端运行中可定位问题的诊断事实，安全地记录在手机本地，并在已有电脑会话可用时可靠地交给电脑端。

它是观察者，不是控制者：诊断模块不得改变 DJI、连接、遥测、图传、航线或 Android 生命周期的业务决定。任何诊断记录、落盘、编码、发送和确认失败，都不得使被观察的业务失败、阻塞或重试。

## 2. 二级模块

| 二级模块 | 唯一职责 | 明确不负责 |
| --- | --- | --- |
| `diagnostic-core` | 定义脱敏事件、稳定事件码、运行批次、顺序队列和确认删除规则 | Android、文件、Logcat、WebSocket、DJI |
| `android-diagnostic-adapter` | 将已脱敏事件写入 Logcat 和应用私有待确认事件快照，并在进程重启后恢复待传事件 | 制定脱敏规则、建立网络连接、解释业务状态 |
| `gateway-diagnostic-publisher` | 仅使用 `relay-gateway` 已有 ACTIVE 会话，按顺序发送待传诊断并处理电脑确认 | 创建 WebSocket、写本地文件、调用 DJI 或业务模块 |

每个二级模块只依赖其契约中列出的公开接口。`diagnostic-core` 必须保持纯 JVM；Android 与 gateway 适配器不能把平台类型泄漏进核心模型。

## 3. 对外数据与隐私

每条诊断事件均由以下字段组成：

```text
timestampMillis | level | module | eventCode | runId | sequence | operationId? | safeDetail
```

- `runId` 标识一次应用进程运行；`sequence` 在同一 `runId` 内从 1 连续递增。
- `module` 和 `eventCode` 是稳定、可检索的机器标识；`safeDetail` 是给开发者理解上下文的短文本。
- 任意事件都不得包含 DJI API Key、RTMP 密钥、认证信息、完整 URL、完整航线内容、绝对路径、原始异常堆栈、用户私密内容或完整第三方对象。
- 手机本地与电脑端都必须把 `(deviceId, runId, sequence)` 作为去重键。

诊断容量是有界的。容量耗尽时，模块必须保留最近事件并丢弃最旧的未确认事件，同时生成一条可被发送的 `DIAGNOSTIC_EVENTS_DROPPED` 事件；不得无限制占用内存、磁盘或网络。

## 4. 无线交付语义

手机只经 `relay-gateway` 的当前 `ACTIVE` 会话发送 `diagnostic-report`。手机不得为日志新建 HTTP、WebSocket、广播或局域网监听端口。

1. `gateway-diagnostic-publisher` 读取队列最早的连续事件，最多组成一个固定上限的批次。
2. 出站写入被 gateway 接受后，事件仍然保留在待确认队列；写入被拒绝、断线或应用重启时，事件保持待传。
3. 电脑端持久化全部事件后，发送 `diagnostic-ack`，确认同一 `runId` 内 `sequence <= acknowledgedSequence` 的事件。
4. 手机只在收到合法确认后删除对应事件。重复报告和重复确认必须幂等。
5. 新会话进入 `ACTIVE` 时，必须从最早未确认事件开始顺序补传。不同 `runId` 之间不合并确认。

这提供至少一次投递，不承诺恰好一次投递；电脑端去重使用户看到的结果等价于一次。

## 5. 电脑端协作义务

电脑端收到 `diagnostic-report` 后，必须先将每一条合法事件持久化到本地循环日志，再发送 `diagnostic-ack`。不得在未完成持久化时确认，也不得仅显示在界面内存中后确认。

电脑端必须按 `(deviceId, runId, sequence)` 去重，并允许重复帧和断线补传。日志查看界面至少应支持按设备、运行批次、模块、事件码、时间和等级筛选，并能导出脱敏后的文本。

尚不支持该帧类型的旧电脑端必须忽略 `diagnostic-report`，而非关闭会话；手机会保留待传事件。尚不支持确认的旧手机端必须忽略 `diagnostic-ack`。在电脑端完成接收与确认实现以前，手机端只能声明“本地日志可用、无线投递协议已准备”，不能声明端到端无线日志可用。

## 6. 验收

- 纯 JVM 测试覆盖事件字段限制、脱敏、顺序、容量淘汰、重复确认、错误 `runId`、断线后补传和 sink 失败隔离。
- 协议测试覆盖两种诊断帧的编码、解码、字段类型和所有固定上限。
- gateway 测试覆盖仅 ACTIVE 时发送、出站方向限制、确认分发和旧会话隔离。
- Android 适配器测试覆盖原子快照写入、进程恢复、Logcat/文件写入失败隔离和不记录敏感文本。
- 真机阶段以电脑端持久化日志、重启手机后补传、断网再联网补传三项实测作为端到端验收依据。

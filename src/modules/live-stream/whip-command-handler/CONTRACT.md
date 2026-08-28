# whip-command-handler 二级模块契约

状态：已封存的 WebRTC/WHIP 旁路源码与独立测试；不纳入生产组合根。

> 封存规则：本模块只保留给历史旁路的源码和测试，生产 RTMP `LiveStream`、`MobileRelayGraph`、命令注册和 APK 依赖均不得调用或装配它。重新启用必须先取得业务批准，并同步更新两端根契约、生产装配和跨端验证。
Gradle 路径：`:live-stream:whip-command-handler`

## 唯一职责

`whip-command-handler` 只解释 `live-stream-webrtc.start` 和 `live-stream-webrtc.stop`，校验字段，并把已接受动作交给注入的 WHIP 图传动作。它不调用 DJI、不建立网络、不保存状态。

## 命令字段

启动只接受一个字符串字段 `whipUrl`：

```text
live-stream-webrtc.start { whipUrl: string }
live-stream-webrtc.stop  {}
```

字段必须精确匹配，不能同时携带 `rtmpUrl`、`protocol` 或未知字段。启动在配置校验完成前不得调用动作。接受只表示提交，不得立即生成成功结果；终态必须通过注入的 completion 报告。

## 对外接口

```kotlin
interface WhipCommandActions {
    fun start(config: ValidatedWhipStreamConfig, completion: WhipActionCompletion): WhipActionResult
    fun stop(completion: WhipActionCompletion): WhipActionResult
}

fun interface WhipActionCompletion {
    fun complete(outcome: WhipActionTerminalOutcome)
}

enum class WhipActionTerminalOutcome { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED }

sealed interface WhipActionResult {
    data object Accepted : WhipActionResult
    data object Rejected : WhipActionResult
}

sealed interface WhipCommandResult {
    data object Accepted : WhipCommandResult
    data class Rejected(val reason: WhipCommandRejection) : WhipCommandResult
}

enum class WhipCommandRejection {
    UNKNOWN_COMMAND, INVALID_FIELDS, INVALID_CONFIGURATION, CAPABILITY_REJECTED
}

class WhipCommandHandler {
    fun handle(command: CommandFrame): WhipCommandResult
    fun handle(command: CommandFrame, completion: WhipActionCompletion): WhipCommandResult
}
```

`WhipCommandHandler.create(actions)` 是唯一构造入口。处理器无状态、同步、线程安全；它不创建线程、不保存命令或配置。动作返回 `Rejected`、动作抛出任意异常或输入不符合命令字段契约时，只返回固定拒绝枚举。原始 URL、异常文本、凭据和 DJI/平台对象不得进入结果。

传给动作的 completion 对每个命令最多生效一次；动作重复调用 completion、并发调用 completion 或在动作返回拒绝后调用 completion，都不能让外部观察到第二个终态。处理器不把动作接受提交伪装成成功。

## 验收

覆盖精确字段、全部 URL 拒绝原因、动作委托、同步拒绝、接受与终态的时机、重复终态、未知命令、并发独立读取和异常脱敏。

# sdk-lifecycle 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`device-connection`
Gradle 路径：`:device-connection:sdk-lifecycle`

## 1. 唯一职责

`sdk-lifecycle` 只负责让 DJI SDK 进入可用或不可用状态，并把第三方回调转换为安全的生命周期状态。它不读取遥控器或飞行器状态，不执行配对、直播、航线或遥测操作。

## 2. 对外接口

```text
SdkLifecycle.create(port, diagnosticSink?, startupTimeoutScheduler?) -> SdkLifecycle
SdkLifecycle.start() -> StartAccepted | AlreadyRunning | StartRejected
SdkLifecycle.stop() -> Stopped | AlreadyStopped
SdkLifecycle.state() -> SdkAvailability
SdkLifecycle.onChanged(listener) -> Registration
```

内部适配 seam：

```text
DjiSdkPort.initialize(callbacks) -> Accepted | Rejected(safeReason)
DjiSdkPort.close()
DjiSdkCallbacks.onReady()
DjiSdkCallbacks.onFailure()
SdkStartupTimeoutScheduler.schedule(delayMillis, callback) -> SdkStartupTimeout
SdkStartupTimeout.cancel()
```

`DjiSdkPort` 只存在于本模块的实现和测试边界。公开结果不得包含 DJI `IDJIError`、Android `Context`、App Key、异常消息或堆栈。

## 3. 状态和时序

```text
STOPPED -> STARTING -> READY
STARTING -> FAILED
READY -> STOPPED
FAILED -> STOPPED
```

- `start()` 从 `STOPPED` 进入 `STARTING`，然后只调用一次 `port.initialize`。
- `initialize` 返回 `Accepted` 只表示初始化请求已接收；只有 `onReady()` 到达后才进入 `READY`。
- `start()` 在调用 `initialize` 前为当前运行代次启动一个 30 秒倒计时。若届时仍未收到有效的 `onReady()` 或 `onFailure()`，生命周期必须进入 `FAILED` 并记录 `START_TIMEOUT`；它不能永久停在 `STARTING`。
- `initialize` 同步回调 `onReady` 或 `onFailure` 必须被正确处理。
- `start()` 在 `STARTING`、`READY` 或 `FAILED` 时不重复初始化；`FAILED` 必须先 `stop()` 才能再次 `start()`。
- `READY`、`FAILED` 和 `stop()` 都必须取消当前代次的倒计时。`stop()` 使当前运行代次失效，然后调用一次 `port.close()`；旧代次之后到达的回调或倒计时必须丢弃。
- 生命周期状态通知按状态转换顺序发送；监听器异常不得改变状态或阻止其他监听器。

`SdkStartupTimeoutScheduler` 是本模块唯一的时间接缝。生产实现可以使用进程内调度器；纯 JVM 测试必须注入受控调度器。该接缝不得暴露 Android、DJI 或网络类型，也不得由调用方用来改变超时长度。

## 4. 失败和隐私

初始化拒绝、回调失败或启动超时进入 `FAILED`，返回稳定的 `StartRejected`/诊断结果，不假装 SDK 可用。超时诊断固定为 `START_TIMEOUT`，不得携带第三方异常。`stop()` 即使底层 `close()` 抛异常也必须进入 `STOPPED`。失败不会全局销毁 DJI MSDK；调用方必须先 `stop()`，再发起新的本地生命周期代次。

## 5. 测试要求

纯 JVM 测试必须覆盖：首次启动、重复启动、同步 ready、异步 ready、初始化拒绝、初始化失败、启动超时、ready/failure/stop 取消倒计时、旧代次回调或倒计时、close 异常、监听器异常和状态通知顺序。真实 MSDK 注册只能在 Android 集成测试中覆盖。

# foreground-service 模块契约

状态：已批准实现
版本：1.0.0
所属一级模块：app-runtime
Gradle 路径：:app-runtime:foreground-service

## 唯一职责

本模块持有保持中继进程存活的 Android 前台服务生命周期状态，将启动/停止请求转换为平台端口调用，并且只接受当前操作的终态回调。

它不创建或停止 WebSocket 会话，不调用 DJI，不持有设置或业务状态，不请求权限，不创建通知文字，也不读取 Activity/Service 全局对象。Android 适配器独占通知渠道、服务 Intent 和实际 `startForeground` 调用。

## 对外接口

```text
ForegroundServiceController.create(port, diagnosticSink?) -> controller
controller.start() -> Accepted | Rejected(ALREADY_RUNNING | TRANSITION_IN_PROGRESS | PORT_FAILURE)
controller.stop()  -> Accepted | Rejected(ALREADY_STOPPED | TRANSITION_IN_PROGRESS | PORT_FAILURE)
controller.snapshot() -> STOPPED | STARTING | RUNNING | STOPPING | FAILED
controller.onChanged(listener) -> Registration
```

`ForegroundServicePort.start(callback)` 与 `stop(callback)` 是唯一平台接缝。端口正常情况下最多调用一次回调，但控制器必须防御性忽略重复、延迟和跨操作回调。接受启动/停止只表示平台请求已提交；只有匹配的终态回调才能报告 `RUNNING` 或 `STOPPED`。

控制器同步且线程安全，同时只能有一个迁移。端口抛出映射为 `PORT_FAILURE` 并进入 `FAILED`；后续 `start` 可重试。监听器异常必须隔离，不能回滚已提交状态。

## 测试

必须覆盖全部状态、重复启动/停止、接受迁移、端口失败、成功及失败回调、旧操作延迟回调、重复回调、并发调用、监听器失败和注册。

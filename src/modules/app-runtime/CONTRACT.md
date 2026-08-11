# app-runtime 模块契约

状态：已实施并已验证
版本：1.0.0
Gradle 路径：:app-runtime

## 唯一职责

本模块持有 Android 中继运行时生命周期，并按固定顺序组合运行时职责：权限、前台服务、随后是注入的业务模块。`android-permission-adapter` 由 Android 应用组合层作为具体 `PermissionPort` 提供，并不是该平台无关门面的依赖。本模块是唯一允许组合这些运行时职责的模块。

它不实现 Android 权限 API、通知渠道、WebSocket 会话、DJI 操作、设置、遥测、图传或航线任务。这些职责只通过所属模块的对外接口或由应用提供的适配器进入。

## 对外接口

```text
AppRuntime.create(permissionCoordinator, foregroundService, bootstrap) -> AppRuntime
runtime.start(requiredPermissions)
  -> Accepted(cancellation)
  | AlreadyRunning
  | TransitionInProgress
  | Rejected(PERMISSION_REQUEST | FOREGROUND_SERVICE | MODULES)
runtime.stop() -> Accepted | AlreadyStopped | TransitionInProgress | Rejected(STOP_FAILURE)
runtime.snapshot() -> STOPPED | WAITING_PERMISSIONS | STARTING_SERVICE | STARTING_MODULES | RUNNING | STOPPING | FAILED
runtime.onChanged(listener) -> Registration
```

`start` 被接受只表示已开始工作，不表示服务或业务模块已经就绪。它先请求给定权限集，只有权限成功完成后才启动前台服务，随后启动 `AppBootstrap`。`stop` 必须先停止业务模块，再停止前台服务。权限被拒绝/取消、服务失败或模块失败时，按终态进入 `FAILED` 或 `STOPPED`，且绝不报告 `RUNNING`。

任一时刻只能有一个生命周期迁移。重复和并发调用必须确定性处理。取消仅在等待权限时有效；运行时离开该操作后，延迟权限/服务回调必须忽略。监听器失败必须隔离。任何结果不得暴露 Android 对象、权限名、异常消息、通知数据或业务状态。

## 测试

必须覆盖已授予权限启动、异步权限完成、拒绝/取消、服务失败与同步回调、模块失败时清理服务、正常逆序停止、重复/并发调用、延迟回调、监听器失败及注册。

# permission-coordinator 模块契约

状态：已批准实现
版本：1.0.0
所属一级模块：app-runtime
Gradle 路径：:app-runtime:permission-coordinator

## 唯一职责

本模块协调 Android 运行时权限和 USB 访问授权请求，向 `app-bootstrap` 与 `foreground-service` 提供稳定、平台无关的快照和请求生命周期。

它不决定业务命令是否允许，不调用 DJI，不启动服务，不读取 Activity 或 Service 对象，不持久化设置，也不把权限结果翻译为用户界面文字。

## 对外接口

```text
PermissionCoordinator.create(port) -> PermissionCoordinator
coordinator.snapshot() -> PermissionSnapshot
coordinator.request(required, listener)
  -> Started(cancellation)
  | AlreadySatisfied(snapshot)
  | Rejected(EMPTY_REQUEST | ALREADY_IN_PROGRESS)
coordinator.onChanged(listener) -> Registration

PermissionPortCallback.completed(snapshot) -> 平台终态结果
PermissionPortCallback.failed() -> 平台终态失败
```

`PermissionKind` 的稳定集合为 `RUNTIME` 和 `USB_ACCESS`。`PermissionState` 为 `UNKNOWN`、`GRANTED`、`DENIED` 或 `PERMANENTLY_DENIED`。Android 适配器实现 `PermissionPort`，并独占实际权限字符串、Activity Result API、USB 广播和生命周期绑定。

同一时刻最多一个请求有效。一个请求只会以 `Completed(snapshot)`、`Denied(snapshot)` 或 `Failed` 完成一次。取消是幂等操作，产生 `Cancelled`；被取消操作或任一旧操作的回调必须忽略。仅请求已授予权限时不得调用端口。

端口回调是终态，可以从任意线程、多次或取消后到达。只有所有请求种类均被授予时，`completed(snapshot)` 才成为 `Completed`；否则成为 `Denied`。`failed()` 成为 `Failed`。协调器串行化状态变更，忽略过期/重复回调并隔离监听器异常。回调或失败不得暴露 Android 对象、权限字符串、异常消息或堆栈。

## 测试

必须覆盖初始及全部权限状态、空请求、已授予请求、接受请求、重复请求、完成/拒绝/失败、取消与延迟回调、重复回调、并发请求、端口抛出、监听器抛出、注册以及快照不可变性。

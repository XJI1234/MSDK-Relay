# android-foreground-service-adapter 模块契约

状态：已实现并已验证
版本：1.0.0
所属一级模块：app-runtime
逻辑 Gradle 路径：:app-runtime:android-foreground-service-adapter

## 唯一职责

本模块实现 `ForegroundServicePort` 接缝：启动和停止一个中继前台服务，创建通知渠道和通知，并报告该服务是否实际进入或离开前台执行。

它不启动 relay-gateway、DJI、遥测、图传、航线或设置模块，也不决定中继何时运行。纯 `ForegroundServiceController` 持有迁移规则，`AppRuntime` 持有启动顺序，Android 应用只提供通知资源。

## 对外接口

```text
AndroidForegroundServicePort.create(applicationContext, notificationSpec) -> AndroidForegroundServicePort
port implements ForegroundServicePort
port.start(callback) -> Unit
port.stop(callback) -> Unit
port.close() -> Unit
```

`notificationSpec` 包含稳定渠道 ID、渠道名称资源 ID、通知文本资源 ID、通知 ID 和小图标资源 ID；不含业务状态，且服务运行期间不得变化。`create` 在首次启动前注册非导出、仅本包可见的接收器。组合根只在控制器停止服务后调用 `close` 注销它。

## 启动和停止规则

1. `start` 生成一个不透明操作 ID，保存回调，并在 Android O 及以上使用 `ContextCompat.startForegroundService`。
2. `RelayForegroundService` 创建渠道并在报告 `started` 前调用 `startForeground`；此前失败报告 `failed`。
3. 端口只接受匹配的操作 ID；重复、延迟、外来或 `close` 后广播必须忽略。
4. 未观察到运行服务时 `stop` 立即完成；否则向中继服务发送显式停止命令，服务在销毁路径发布 `stopped`。不得使用隐式 Intent。
5. 同时只能有一个端口操作。绕开纯控制器的第二个直接调用会得到 `IllegalStateException`。
6. `close` 是幂等操作，禁止后续回调，且绝不启动、停止或重启业务模块。

## 通知与安全规则

- Android O 及以上恰好创建一个低重要性通知渠道。
- 服务通知必须常驻并使用配置的小图标。
- 所有控制和状态 Intent 必须显式指向本应用包。
- 平台支持时状态接收器以 `RECEIVER_NOT_EXPORTED` 注册；不得新增导出接收器或可绑定服务。
- Android 异常、Intent extra、通知数据和堆栈不得越过 `ForegroundServicePort`。

## 验证要求

测试必须覆盖端口边界的启动、停止和失败终态回调；精确操作 ID 匹配、错误方向、重复、延迟、外来及 `close` 后回调；重复直接调用、回调隔离、同步平台抛出和平台资源释放；通知规格校验。Android 构建必须验证服务声明、前台服务权限及 `startForeground` 路径编译。真机仪表测试属于应用集成测试，因为库不持有宿主资源或测试运行器。

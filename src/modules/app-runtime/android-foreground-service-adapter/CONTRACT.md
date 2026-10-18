# android-foreground-service-adapter 模块契约

状态：已实现并已验证
版本：1.0.0
所属一级模块：app-runtime
逻辑 Gradle 路径：:app-runtime:android-foreground-service-adapter

2026-10-14 已补齐服务销毁路径：正常停止发布 stopped，非预期销毁发布 activeStart 对应 failed，启动失败消费该操作、不在销毁时重复发布。无活动操作的空 intent 调用 stopSelfResult，不创建缺失业务图；已有活动操作收到空 intent 不停止健康服务。终态规则由 Android 服务实际使用的 ForegroundServiceLifetime 持有，独立测试并贯通真实端口验证。正式容器接线仍待实施，见 [运行容器扩展契约](../../../../docs/auto-discovery/RUNTIME-CONTRACT.md)，不把 START_STICKY 当作业务恢复证明。

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
   若 Android 在没有待处理显式停止的情况下销毁正在运行的服务，服务必须在销毁路径发布 `failed`，使端口和 `AppRuntime` 收敛到失败而不会继续报告运行中；启动失败本身只发布一次 `failed`。
5. 同时只能有一个端口操作。绕开纯控制器的第二个直接调用会得到 `IllegalStateException`。
6. `close` 是幂等操作，禁止后续回调，且绝不启动、停止或重启业务模块。

### 熄屏期间的运行资源（2026-10-18）

前台通知本身不保证 CPU 持续运行或 Wi-Fi 保持性能。实际服务在 `startForeground` 成功之后、发布 `started` 之前，必须持有非引用计数的 `PARTIAL_WAKE_LOCK` 和 Wi-Fi 性能锁；只在该中继服务存活期间持有，不依赖 Activity 可见性，不点亮屏幕、不发送 DJI 或连接命令。当前 Android 11 真机使用 `WIFI_MODE_FULL_HIGH_PERF`；Android 14+ 将该模式映射为仅亮屏且应用在前台时生效的低延迟模式，不能把获取成功当作熄屏 Wi-Fi 保护的证明。Doze 仍可能忽略 CPU 唤醒锁；网络和厂商限制也仍需真机验证，持锁不代表时延达标。

重复 start 不重复获取。获取 Wi-Fi 锁失败必须回滚 CPU 锁，服务启动失败必须释放已取得的资源；显式停止、非预期销毁和重复清理都须释放资源，释放一项失败仍尝试释放另一项。两种锁分别记录所有权，只有平台释放成功才能清除，释放失败保留给后续销毁路径重试。空启动和未知 action 不获取资源。所需普通权限在本库 manifest 声明，不请求忽略系统电池优化或更改用户设置。服务资源的获取失败沿用已有 `failed` 终态，不得假报运行中。

JVM 回归须覆盖成对获取/逆序释放、重复获取/清理、部分获取失败和释放异常；Android 编译验证实际服务接线与 manifest。熄屏前后同链路采集和长时间真机功耗属于安装后的验证，构建通过不能替代它们。

## 通知与安全规则

- Android O 及以上恰好创建一个低重要性通知渠道。
- 服务通知必须常驻并使用配置的小图标。
- 所有控制和状态 Intent 必须显式指向本应用包。
- 平台支持时状态接收器以 `RECEIVER_NOT_EXPORTED` 注册；不得新增导出接收器或可绑定服务。
- Android 异常、Intent extra、通知数据和堆栈不得越过 `ForegroundServicePort`。

## 验证要求

测试必须覆盖端口边界的启动、停止和失败终态回调；精确操作 ID 匹配、错误方向、重复、延迟、外来及 `close` 后回调；重复直接调用、回调隔离、同步平台抛出和平台资源释放；通知规格校验。Android 构建必须验证服务声明、前台服务权限及 `startForeground` 路径编译。真机仪表测试属于应用集成测试，因为库不持有宿主资源或测试运行器。

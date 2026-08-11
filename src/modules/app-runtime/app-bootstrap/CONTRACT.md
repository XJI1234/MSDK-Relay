# app-bootstrap 模块契约

状态：已批准实现
版本：1.0.0
所属一级模块：app-runtime
Gradle 路径：:app-runtime:app-bootstrap

## 唯一职责

本模块不自行创建任何业务模块，而是持有注入运行时模块的有序启动和逆序停止。它是最终 `app-runtime` 门面使用的组合接缝。

它不了解 Android `Activity` 或 `Service`，不请求权限，不创建通知，不连接电脑，不调用 DJI，也不解释业务状态。每个注入模块独自持有自己的平台适配器和契约。

## 对外接口

```text
AppBootstrap.create(modules) -> AppBootstrap
bootstrap.start() -> Started | Rejected(ALREADY_RUNNING | TRANSITION_IN_PROGRESS | MODULE_FAILURE)
bootstrap.stop() -> Stopped | Rejected(ALREADY_STOPPED | TRANSITION_IN_PROGRESS | MODULE_FAILURE)
bootstrap.snapshot() -> STOPPED | STARTING | RUNNING | STOPPING | FAILED
```

`BootstrapModule` 具有稳定名称、`start()` 和 `stop()` 操作。模块按声明顺序启动，必须按完全相反的顺序停止。启动失败时，返回 `MODULE_FAILURE` 前必须按逆序停止所有已启动模块。停止时即使一个模块失败，也必须尝试停止全部已启动模块。失败结果只包含稳定模块名称和阶段，不得包含异常细节。

调用同步且线程安全，同一时刻只能有一个迁移。重复请求不得调用模块而应直接被拒绝。失败后的下一次 `start` 可重试，不得复用部分启动的模块集合。监听器回调刻意不属于本模块；门面读取快照。

## 测试

必须覆盖空模块、正常顺序、逆序停止、重复和并发调用、启动失败回滚、停止失败但继续清理、失败后重试、模块异常和状态迁移。

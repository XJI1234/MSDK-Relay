# dji-stream-adapter 模块契约

状态：已实施并已验证；版本：1.0.0；所属一级模块：live-stream；Gradle 路径：:live-stream:dji-stream-adapter

## 唯一职责与接口

本模块经共享 `DjiOperationCoordinator` 将已校验图传启动/停止请求适配到 DJI 图传 SDK，并将终态 DJI 结果转换为 `stream-state-store` 迁移。它不校验 URL、不解释中继命令、不持有图传事实、不发布遥测、不管理 WebSocket，也不创建执行器或调度器。

```text
DjiStreamAdapter.create(stateStore, djiPort, coordinator, timeoutMillis = 30000)
adapter.start(validatedConfig) -> Accepted(cancellation) | Rejected(reason)
adapter.stop() -> Accepted(cancellation) | Rejected(reason)
```

`DjiStreamPort` 是唯一 DJI 接缝：`start` 接收已校验配置、指标回调和终态完成回调；`stop` 接收终态完成回调。`Accepted` 只表示操作提交，状态只在协调器报告终态时变为活动/非活动。两个请求可接受 `StreamDjiTerminalListener`；对已接受操作，它在对应状态迁移尝试后恰好接收一次安全结果 `SUCCEEDED|FAILED|TIMED_OUT|CANCELLED`；前置条件或提交拒绝同步返回且不调用它。

状态前置条件失败和协调器拒绝返回稳定枚举。适配器异常、DJI 失败、超时、取消、重复完成、延迟指标及延迟回调均转为安全状态迁移。协调器串行化 DJI 调用并提供取消/超时；每个回调携带状态存储返回的操作代际，旧启动/停止不得影响新操作。模块 JVM 线程安全且不持有可变业务状态；公开结果不得暴露 URL 凭据、DJI 对象、原始异常或堆栈。

测试必须覆盖成功启动/停止、指标转发、重复完成、启动/停止前置条件拒绝、适配器异常、协调器拒绝、超时、取消、延迟回调和共享协调器串行操作。

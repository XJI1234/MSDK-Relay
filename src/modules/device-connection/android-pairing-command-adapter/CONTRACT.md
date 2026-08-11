# android-pairing-command-adapter 模块契约

状态：已实现并已验证
版本：1.0.0
所属一级模块：device-connection
逻辑 Gradle 路径：:device-connection:android-pairing-command-adapter

## 唯一职责

本模块是既有 `PairingPort` 接缝的 Android DJI MSDK v5 实现。它只创建可由共享 `DjiOperationCoordinator` 执行的“请求开始配对”和“请求停止配对”操作，将 MSDK 的一次完成回调转换为 `DjiOperation` 的成功或失败。

它不决定操作是否允许、不维护配对状态、不观察配对结果、不管理超时或取消、不调度线程、不持久化状态、不观察设备连接、不发布遥测、不管理图传或航线任务、不建立中继连接、不请求权限，也不渲染用户界面。

## 对外接口

```text
AndroidPairingPort.create() -> PairingPort

port.startPairing() -> DjiOperation
port.stopPairing() -> DjiOperation
```

工厂方法返回既有的平台无关 `PairingPort`。端口和返回的 `DjiOperation` 不得暴露 DJI 键、管理器、回调、错误、Android 对象、配对身份、序列号、产品 ID 或原始异常细节。

创建操作不得立即调用 DJI；只有协调器调用 `DjiOperation.execute` 后，适配器才可执行对应 DJI action。每次 `execute` 必须至多发起一次 DJI action，并对正常回调至多完成一次。重复、延迟或旧代际完成回调必须忽略。

## 操作和失败规则

1. `startPairing` 只使用 MSDK 的遥控器 `KeyRequestPairing` action；`stopPairing` 只使用 `KeyStopPairing` action。不得调用中继配对、多设备配对、遥控器升级配对或任何其他 action。
2. MSDK action 成功只调用操作完成器的成功；失败、同步 DJI 异常、action 不可用或回调异常只调用失败。失败不得包含厂商错误码、消息、异常或堆栈。
3. 操作完成器回调必须在适配器锁外执行，且用户完成器异常必须隔离。
4. 适配器不实现超时、排队、串行化或取消；这些职责只能由 `dji-operation-coordinator` 处理。协调器取消后的迟到 DJI 回调不得令同一 `DjiOperation` 第二次完成。
5. 适配器不把 action 成功解释为 `PAIRED` 或 `IDLE`。真实配对状态只能由 `android-pairing-status-adapter` 观察和发布。

## 依赖和验证规则

直接 DJI MSDK 依赖只能存在于本 Android 适配器；它仅为 `PairingPort` 和 `DjiOperation` 依赖 `:device-connection:pairing-controller`，不得依赖状态存储、配对状态链接/适配器、操作协调器、遥控器/飞行器链接、SDK 生命周期、遥测、图传、航线、gateway、app-runtime 或 Android UI 类型。

JVM 测试必须覆盖惰性创建、开始/停止 action 选择、成功、失败、同步异常、重复/延迟完成、完成器异常隔离以及每次 execute 仅发起一次 action。Android Debug 构建必须编译 MSDK v5.17 action 封装；真实设备必须验证开始/停止请求可被 DJI 接受和失败安全映射。状态观察、命令前置条件、超时、取消和调度验证不属于本模块。

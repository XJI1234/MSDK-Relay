# android-pairing-status-adapter 模块契约

状态：已实现并已验证
版本：1.0.0
所属一级模块：device-connection
逻辑 Gradle 路径：:device-connection:android-pairing-status-adapter

## 唯一职责

本模块是既有 `PairingStatusPort` 接缝的 Android DJI MSDK v5 实现。它只观察遥控器报告的真实飞行器配对状态，将厂商状态标准化为平台无关 `PairingState`，并发布 `PairingStatusSignal`。

它不请求开始/停止配对，不保存设备状态，不决定配对命令前置条件，不观察遥控器或飞行器连接、不推断配对状态、不执行 DJI 操作、不发布遥测、不管理图传或航线任务、不建立中继连接、不请求权限，也不渲染用户界面。

## 对外接口

```text
AndroidPairingStatusPort.create() -> PairingStatusPort

port.start(listener) -> PairingStatusSubscription
port.stop() -> Unit
subscription.cancel() -> Unit
```

工厂方法返回既有的平台无关 `PairingStatusPort`。端口及信号不得暴露 DJI 键、管理器、回调、错误、Android 对象、序列号、产品 ID、遥控器型号或原始异常细节。

`start` 最多建立一个有效 MSDK 观察。有效期间重复调用不得注册额外监听器，也不得替换原监听器，并返回空操作订阅。`stop` 和 `subscription.cancel` 均为幂等操作；它们使当前回调代际失效并释放平台监听器。

## 状态映射和信号规则

每个被接受的平台状态转换为：

```text
PairingStatusSignal(sourceRevision, state)
```

1. `sourceRevision` 必须严格为正，对每个已发布信号递增，且在进程存活期间不重置。
2. DJI `UNPAIRED` 映射为 `PairingState.IDLE`，`PAIRING` 映射为 `PAIRING`，`PAIRED` 映射为 `PAIRED`。
3. DJI 的停止中或切换中状态映射为 `STOPPING`；明确停止失败或设备不匹配等终态映射为 `FAILED`；未知或未来厂商值映射为 `UNKNOWN`。映射表以实际 MSDK v5.17 枚举为准，新增厂商枚举值只能安全映射为 `UNKNOWN`，不得伪造成功。
4. 适配器不得从连接状态、USB 状态、历史状态、产品类型、序列号、固件或配对命令结果推断状态。
5. `start` 成功后必须先注册 `RemoteControllerKey.KeyPairingStatus` 的持续监听，再通过 `KeyManager.getValue(key, callback)` 显式向硬件异步读取一次初值。不得调用同步 `getValue(key)`，因为其只读取 MSDK 缓存。读取成功前保持 `UNKNOWN`；读取失败或硬件返回 null 也保持 `UNKNOWN`。每次硬件初读必须记录该 Key 已接收监听事件的版本；读取请求之后先到达的监听事件优先，较晚返回的初读结果必须丢弃，不能反向覆盖新事件。相同值可带更新版本再次发布；跨来源排序和去重属于 `PairingStatusLink` 与状态存储。

## 生命周期、依赖和验证规则

每次成功平台注册有一个回调代际；取消、停止、替代或失败代际的回调必须忽略。用户监听器在适配器锁外执行，其异常必须隔离。无法建立监听时，`start` 只能以稳定原因 `pairing status listener unavailable` 失败；原始 DJI 异常、消息和堆栈不得越过接缝。监听器释放失败必须隔离，不能保留有效代际或放行延迟回调。本模块不使用 Activity、Fragment、View、Context、网络端点、桌面协议数据、App Key 或进程级 SDK 关闭操作。

直接 DJI MSDK 依赖只能存在于本 Android 适配器；它仅为 `PairingStatusPort`、`PairingStatusSignal`、`PairingStatusSubscription` 依赖 `:device-connection:pairing-status-link`，不得依赖 `pairing-controller`、状态存储、遥控器/飞行器链接、SDK 生命周期、遥测、图传、航线、gateway、app-runtime 或 Android UI 类型。最终 Android 组合根只在 DJI SDK 可用后启动端口，本模块不推断或强制该顺序。

JVM 测试必须覆盖全部已知 DJI 配对状态映射、未知值、初始同步回调、递增版本、重复启动、取消、停止、重复停止、取消/停止/重启后的过期回调、监听注册/释放失败和用户监听器异常隔离。Android Debug 构建必须编译 MSDK v5.17 的配对状态键监听器；真实设备必须验证开始、成功、停止、失败、遥控器断连和应用重建后的状态及监听器清理。配对命令、状态存储、遥测、图传和航线验证不属于本模块。

# remote-controller-link 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`device-connection`
Gradle 路径：`:device-connection:remote-controller-link`

## 1. 唯一职责

本模块只负责遥控器连接观察的生命周期和规范化：把适配端提供的遥控器连接信号转换成 `LinkState` 与显示型号，并提交给 `device-state-store`。它不观察飞行器、不推断配对、不执行 DJI 操作、不管理 WebSocket。

适配端的 `connected: Boolean?` 是观察事实而非默认值：`true`、`false`、`null` 分别映射为 `CONNECTED`、`DISCONNECTED`、`UNKNOWN`。未观察或 null 绝不能伪报为断开。

## 2. 对外接口

```text
RemoteControllerLink.create(store, port, diagnosticSink?) -> RemoteControllerLink
link.start() -> Started | AlreadyStarted | Rejected(safeReason)
link.stop() -> Stopped | AlreadyStopped
```

适配 seam：

```text
RemoteControllerPort.start(listener) -> PortSubscription
RemoteControllerPort.stop()
RemoteControllerListener.onChanged(RemoteControllerSignal)
RemoteControllerSignal(sourceRevision, connected, displayModel?)
```

`RemoteControllerSignal` 只允许连接事实和显示型号，不得包含 DJI 对象、序列号、认证信息、原始异常或 Android 类型。`sourceRevision` 必须是适配器为遥控器来源维护的正整数版本。

## 3. 规则

- `start()` 只允许建立一个端口监听；重复启动不建立第二个监听。
- `stop()` 使当前运行代次失效，并调用一次端口停止；停止后的旧回调必须丢弃。
- 有效信号只提交 `DeviceStatePatch.remoteController`，不得覆盖 SDK、飞行器、飞控或配对字段。
- 同一来源的旧版本和重复版本由状态仓库忽略；模块不得自行重排或回放旧信号。
- 适配端注册/停止抛异常时，模块返回稳定结果并记录诊断，不泄漏异常详情。
- 信号字段非法时不改变状态；诊断接收器异常也不得反向影响连接模块。

## 4. 测试要求

必须覆盖首次启动、重复启动、停止、重复停止、正常连接/断开、型号规范化、旧版本、停止后的旧回调、端口异常、非法信号、监听器注销和不覆盖其他设备事实。

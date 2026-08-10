# aircraft-link 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`device-connection`
Gradle 路径：`:device-connection:aircraft-link`

## 1. 唯一职责

本模块只负责飞行器、飞控连接和飞行器显示型号的观察生命周期与规范化，并把它们提交给 `device-state-store`。它不观察遥控器，不处理配对，不执行航线、图传或遥测操作。

## 2. 对外接口

```text
AircraftLink.create(store, port, diagnosticSink?) -> AircraftLink
link.start() -> Started | AlreadyStarted | Rejected(safeReason)
link.stop() -> Stopped | AlreadyStopped
```

适配 seam：

```text
AircraftPort.start(listener) -> PortSubscription
AircraftPort.stop()
AircraftListener.onChanged(AircraftSignal)
AircraftSignal(sourceRevision, aircraftConnected, flightControllerConnected, displayModel?)
```

`AircraftSignal` 只包含飞行器来源事实，不得包含 DJI 对象、序列号、认证信息、原始异常或 Android 类型。

## 3. 规则

- 只创建一个端口监听；重复启动和重复停止幂等。
- 停止使当前运行代次失效，旧代次回调必须丢弃。
- 飞行器断开时强制规范化飞控为断开；不允许产生矛盾快照。
- 有效信号只提交飞行器补丁，不覆盖 SDK、遥控器或配对状态。
- 同一来源旧版本和重复版本由状态仓库忽略。
- 端口异常、信号非法和诊断接收器异常均被隔离，外部只看到稳定结果。
- 注册期同步信号只有在注册成功后才提交；注册失败时全部丢弃。

## 4. 测试要求

覆盖正常连接/断开、飞控关系规范化、型号、版本隔离、生命周期幂等、停止后旧回调、注册期同步回调、注册失败、非法信号和不覆盖其他来源字段。

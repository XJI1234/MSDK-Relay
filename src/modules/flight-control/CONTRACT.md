# flight-control 模块契约

状态：已实现；实机待验证
Gradle 路径：`:flight-control`

## 唯一职责

`flight-control` 负责把桌面端的高风险飞行命令安全地提交给 DJI 飞控，并仅在 DJI 明确完成对应 Action 调用后返回终态结果。它支持 `flight.takeoff`、`flight.land`、`flight.confirm-landing`、`flight.return-home`、`flight.stop-takeoff`、`flight.stop-auto-landing` 六个命令。

它不负责连接 DJI、读取遥测、生成或执行航线、控制虚拟摇杆、维护相机设置，也不根据遥测猜测命令是否完成。

## 对外接口

```text
FlightControl.create(FlightControlDependencies) -> FlightControl
flightControl.commandHandler() -> CommandHandler
flightControl.observeDjiFlightState(fact) -> Unit
flightControl.markDeviceUnavailable() -> Unit
flightControl.close() -> Unit
```

每个命令必须只有字段 `{ "confirm": true }`。任何缺少确认、额外字段、字段类型错误或未知命令都在调用 DJI 前被拒绝。`confirm` 是桌面端对每次实际飞行操作的明确确认，不可缓存、不可默认补全。

飞行门面只收敛用户意图的重入，不以遥测或设备事实替 DJI 判断是否允许动作：任一飞行命令尚未结束时不得再次提交 `TAKEOFF`，同一种收尾动作也不得重复提交。这样不会在回执延迟后执行陈旧的重复起飞/重复降落。不同的、已明确确认的收尾动作仍可交给飞行域协调器；若一个普通飞控操作仍等待回执，协调器可以按 `CONTAINMENT` 规则优先尝试该收尾动作。这个本地重入约束不判断飞机状态，也不把 DJI 拒绝改写为本地拒绝。

成功仅表示 DJI 已确认接收并完成对应 Action 调用；实际飞行状态由遥测模块独立上报。`flight.land` 成功不表示已着陆，桌面端必须继续等待 `KeyIsFlying=false` 且 `KeyAreMotorsOn=false`。当 `KeyIsLandingConfirmationNeeded=true` 时，只有操作者再次显式确认的 `flight.confirm-landing` 才可调用 DJI 的继续降落动作；本模块绝不自动确认或重试。MSDK 的 `onFailure(IDJIError)` 必须保留其 `errorCode()` 与本地化 `description()`，在 Android 适配器规范化后作为 `command-result.result` 内的通用飞行动作拒绝摘要 `{ domain: "flight", outcome: "ACTION_REJECTED", errorCode, errorDescription }` 回传。同步异常、超时、取消、设备不可用、重复或延迟回调不得伪装成动作拒绝；前两类必须明确报告为调用失败或结果未确认，且均至多生成一条受限失败结果。

## 二级模块

| 模块 | 职责 | 不负责 |
| --- | --- | --- |
| `flight-command-handler` | 解析命令、严格校验 `confirm`、把合法动作交给门面 | 飞行状态、线程、DJI 调用 |
| `dji-flight-adapter` | 经飞行域操作协调器串行提交动作，统一超时、取消和终态 | 协议解析、Android SDK 类型 |
| `android-dji-flight-adapter` | 唯一调用 MSDK v5 飞控 Action Key 的端口实现 | 命令校验、超时、排队或业务状态 |

## 所有权和失败规则

只有 `android-dji-flight-adapter` 接触 `FlightControllerKey` 和 `KeyManager`；所有飞行操作必须通过 `device-connection` 的 `flightOperations()` 协调器。该域只串行飞控写操作，不得与航线、配对、设备设置或图传互占槽位。组合根在收到飞行遥测模块的每个 MSDK 监听事实时调用 `observeDjiFlightState(fact)`；门面只转交该纯 Kotlin 事实，不能读取 Android、桌面状态或缓存。设备不可用或应用关闭时门面必须取消尚未完成的操作；迟到回调不得恢复或完成已失效的命令。若一个已开始飞行调用超时或取消，飞行域协调器保留本域操作槽位并拒绝普通新飞控写调用，直至 DJI 回执或该动作的权威状态观察确认硬件已稳定；明确的降落、返航与停止类收尾命令按协调器的 `CONTAINMENT` 规则仍可尝试送达 MSDK。超时隔离不得拒绝航线、设置、配对或图传提交。

## 验证要求

各二级模块必须有中文契约和独立测试。测试至少覆盖严格字段校验、确认要求、六个动作与 DJI Action Key 的一对一映射、串行性、DJI 成功/失败、超时、取消、设备断开、重复起飞和重复同一收尾动作拒绝、普通命令在途时收尾命令接管、迟到回调、以及每个网关命令最多一个结果。

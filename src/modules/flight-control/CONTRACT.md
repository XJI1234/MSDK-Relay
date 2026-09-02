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
flightControl.markDeviceUnavailable() -> Unit
flightControl.close() -> Unit
```

每个命令必须只有字段 `{ "confirm": true }`。任何缺少确认、额外字段、字段类型错误或未知命令都在调用 DJI 前被拒绝。`confirm` 是桌面端对每次实际飞行操作的明确确认，不可缓存、不可默认补全。

成功仅表示 DJI 已确认接收并完成对应 Action 调用；实际飞行状态由遥测模块独立上报。`flight.land` 成功不表示已着陆，桌面端必须继续等待 `KeyIsFlying=false` 且 `KeyAreMotorsOn=false`。当 `KeyIsLandingConfirmationNeeded=true` 时，只有操作者再次显式确认的 `flight.confirm-landing` 才可调用 DJI 的继续降落动作；本模块绝不自动确认或重试。失败、超时、取消、设备不可用、重复或延迟回调均至多生成一条不含 SDK 错误、密钥、路径或异常详情的失败结果。

## 二级模块

| 模块 | 职责 | 不负责 |
| --- | --- | --- |
| `flight-command-handler` | 解析命令、严格校验 `confirm`、把合法动作交给门面 | 飞行状态、线程、DJI 调用 |
| `dji-flight-adapter` | 经共享操作协调器串行提交动作，统一超时、取消和终态 | 协议解析、Android SDK 类型 |
| `android-dji-flight-adapter` | 唯一调用 MSDK v5 飞控 Action Key 的端口实现 | 命令校验、超时、排队或业务状态 |

## 所有权和失败规则

只有 `android-dji-flight-adapter` 接触 `FlightControllerKey` 和 `KeyManager`；所有飞行操作必须通过 `device-connection:dji-operation-coordinator`，因此不会与航线和图传 SDK 操作并发重叠。设备不可用或应用关闭时门面必须取消尚未完成的操作；迟到回调不得恢复或完成已失效的命令。若一个已开始飞行调用超时或取消，协调器保留操作槽位并拒绝任何新的 DJI 写调用，直至 DJI 回执或该动作的权威状态观察确认硬件已稳定。

## 验证要求

各二级模块必须有中文契约和独立测试。测试至少覆盖严格字段校验、确认要求、六个动作与 DJI Action Key 的一对一映射、串行性、DJI 成功/失败、超时、取消、设备断开、重复和迟到回调、以及每个网关命令最多一个结果。

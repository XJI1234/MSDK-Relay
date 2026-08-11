# snapshot-assembler 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`telemetry`
Gradle 路径：`:telemetry:snapshot-assembler`

## 1. 唯一职责

本模块把同一个不可变设备状态快照组装成一次对外遥测值，并将内部设备能力转换为电脑端稳定的能力值。它不读取 DJI、不维护第二份设备状态、不编码 JSON、不建立网络连接，也不负责持续发布。

## 2. 对外接口

```text
SnapshotAssembler.assemble(deviceSnapshot) -> TelemetrySnapshot
```

输出只能包含手机端契约允许的安全值：版本、SDK/遥控器/飞行器/飞控/配对状态、显示型号和 `TelemetryCapabilities`。不得包含 DJI 类型、异常、Android 路径、密钥、`DeviceCapabilities` 或内部对象引用。

## 3. 规则

- 一次调用只使用传入的同一个不可变快照，结果不可变。
- 不可用事实使用明确的枚举或空值，不用 `0`、空字符串伪造未知数据。
- 内部能力必须通过 `DeviceCapabilityReader` 从同一快照推导，再唯一交给 `CapabilityCalculator` 转换为 `TelemetryCapabilities`；不能在本模块重复维护判断规则或暴露内部能力类型。
- 组装是纯函数：相同输入必须得到相同输出，不产生副作用。

## 4. 测试要求

覆盖初始状态、完整连接状态、配对状态、内部能力到公开能力的转换、可选型号为空、结果不可变和纯函数重复调用。
